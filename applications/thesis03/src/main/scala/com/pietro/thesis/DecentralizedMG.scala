package com.pietro.thesis

// scalastyle:off println
import java.util.HashMap
import scala.io.Source
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Calendar
import scala.util.Random
import scala.util._
import scala.collection.mutable.WrappedArray

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}

import org.apache.spark.{SparkConf, SparkEnv, SparkContext}
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream._
import org.apache.spark.streaming.kafka010._
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.clients.producer._
import org.apache.spark.rdd.RDD
import java.util.Properties
import java.io.FileInputStream
import twitter4j._
import org.json._
import org.apache.log4j.LogManager


import org.apache.log4j._
import scala.concurrent.duration._

import org.apache.spark.sql.{Row, SparkSession, Column, ForeachWriter}
import org.apache.spark.sql.expressions.{MutableAggregationBuffer, UserDefinedAggregateFunction}
import org.apache.spark.sql.types._

import java.sql.Timestamp

import org.apache.spark.sql.types._
//import org.apache.spark.annotation.InterfaceStability
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, Complete}
import org.apache.spark.sql.execution.aggregate.ScalaUDAF

object DecentralizedMGMerge {
  def main(args: Array[String]) {

    val prop = new Properties()
    prop.load(new FileInputStream("app.properties"))
    val inputKafkaTopic = prop.getProperty("dec.input.topic")
    val inputKafkaBroker = prop.getProperty("dec.input.brokers")

    val outputTopic = prop.getProperty("dec.output.topic")
    val outputBroker = prop.getProperty("dec.output.brokers")

    val test = prop.getProperty("test").toBoolean

    val windowLength = prop.getProperty("dec.windowlength.minutes")
    val sliding = prop.getProperty("dec.sliding.minutes")

    val maxOffsetsPerTrigger = prop.getProperty("dec.maxOffsetsPerTrigger")
    val prefix = prop.getProperty("dec.prefixMetrics") + ".customMetrics"

    import org.apache.spark.sql.streaming.StreamingQuery    
    import org.apache.spark.sql.functions._

    val spark = SparkSession
        .builder()
        .appName("DecentralizedMGMerge")
        .getOrCreate()

    spark.conf.set("spark.sql.streaming.metricsEnabled", "true")

    import spark.implicits._

    import org.apache.spark.sql.types.DataTypes
    import scala.collection.mutable.ListBuffer
    import java.util.ArrayList

    /* Create schema for summary field in MGSummary */
    val summaryFields = new ArrayList[StructField]()
    summaryFields.add(DataTypes.createStructField("value", DataTypes.StringType, true))
    summaryFields.add(DataTypes.createStructField("frequency", DataTypes.LongType, true))
    if(test) summaryFields.add(DataTypes.createStructField("exactFrequency", DataTypes.LongType, true))
    val summaryStruct = DataTypes.createArrayType(DataTypes.createStructType(summaryFields))

    /* Create schema for MGSummary*/
    val mgs = new ArrayList[StructField]()
    mgs.add(DataTypes.createStructField("window", new StructType().add("start", TimestampType).add("end", TimestampType), true))
    mgs.add(DataTypes.createStructField("size", DataTypes.LongType, true))
    if(test) mgs.add(DataTypes.createStructField("clusterDistinctValues", DataTypes.LongType, true))
    if(test) mgs.add(DataTypes.createStructField("error", DataTypes.DoubleType, true))
    if(test) mgs.add(DataTypes.createStructField("correctValues", DataTypes.DoubleType, true))
    if(test) mgs.add(DataTypes.createStructField("processedElements", DataTypes.LongType, true))
    mgs.add(DataTypes.createStructField("summary", summaryStruct, true))
    mgs.add(DataTypes.createStructField("clusterId", DataTypes.StringType, true))

    val schema = DataTypes.createStructType(mgs)

    val orderingTest = udf { list: WrappedArray[Row] => list.map(r => (r.getString(0), r.getLong(1), r.getLong(2))).sortWith(_._2 > _._2)}
    val orderingNotTest = udf { list: WrappedArray[Row] => list.map(r => (r.getString(0), r.getLong(1))).sortWith(_._2 > _._2)}
    val ordering = if(test) orderingTest else orderingNotTest

    val records = spark
        .readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", inputKafkaBroker)
        .option("subscribe", inputKafkaTopic)
        .option("startingOffsets", "latest")
	.option("failOnDataLoss", "false")
//	.option("maxOffsetsPerTrigger", maxOffsetsPerTrigger.toInt)
        .load()
        .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)") // cast key and value as string to be read in the following

    /* Extract 'value' according to provided schema and adding column with timestamp */
    val parsedRecords = records
	.select(from_json($"value", schema) as "parsed")
        .select($"parsed.*")

    spark.udf.register("decentralizedSummary", CreateDecentralizedMGSummaryTest)

    import org.apache.spark.sql.expressions.UserDefinedAggregateFunction

    lazy val decentralizedSummaryTest: UserDefinedAggregateFunction = CreateDecentralizedMGSummaryTest
    lazy val decentralizedSummaryNotTest: UserDefinedAggregateFunction = CreateDecentralizedMGSummaryNotTest
    lazy val decentralizedSummary = if(test) decentralizedSummaryTest else decentralizedSummaryNotTest

    val stddev = udf { list: WrappedArray[Row] => {
          val diffs = list.map( r => (r.getString(0), r.getLong(2) - r.getLong(1)) )
	  var avg = 0.0
          if(diffs.size != 0) avg = diffs.map(_._2).sum / diffs.size
  
          val num = diffs.map( d => {
             (d._2 - avg)*(d._2 - avg)
          }).sum
  
          Math.sqrt(num/(list.size - 1))
        }
     }

    /* Windowed computation: count all elements and sum them up */
    val windowedRecords = parsedRecords
	.withColumnRenamed("window", "summaryWindow") // window field of each record is taken as input from udaf
	.withColumn("windowStart", $"summaryWindow.start")
        .withWatermark("windowStart",  windowLength+" minute")
	.groupBy(window($"windowStart", windowLength+" minute", sliding+" minute"))
	.agg(decentralizedSummary($"error", $"summary", $"clusterId", $"window", $"summaryWindow") as "mergedSummary")
	.select($"mergedSummary.*")
	.filter($"window.end".as("timestamp") < current_timestamp()) // filter window starting now and ending 'windowLength' in the future
	.withColumn("stddev", stddev($"globalSummary"))
	
    windowedRecords.printSchema() // final schema

    import org.apache.spark.sql.streaming.{OutputMode, Trigger, ProcessingTime}

    /* Produce to Kafka topic */
    val toKafka = windowedRecords
	.selectExpr("CAST(window AS STRING) AS key", "to_json(struct(*)) AS value")
	.writeStream
	.outputMode("append")
	.trigger(ProcessingTime(60.seconds))
    	.format("kafka")
	.option("kafka.bootstrap.servers", outputBroker)
    	.option("topic", outputTopic)
    	.option("checkpointLocation", "dec-ss") // ComputeClusterLocal-StructuredStreaming
    	.start()

    /* Prepare sending metrics to Graphite */
    import java.net.Socket
    import java.io._
    import java.net._
    import scala.io._

    var totalDataSize = spark.sparkContext.longAccumulator("totalDataSize")
    val writer = new ForeachWriter[Row] {
      var socket: Socket = _

      override def open(partitionId: Long, version: Long) = {
        socket = new Socket(InetAddress.getByName("sky2.it.kth.se"), 2003)
        true
      }

      override def process(value: Row) = {

        val out = new PrintStream(socket.getOutputStream)

        /* Get data from Row */
        val time = (new java.sql.Timestamp(value.getAs[Row](0).getTimestamp(1).getTime).getTime)/1000
        val size = value.getLong(1)
        val error = value.getDouble(2)
        val accuracy = value.getDouble(3)
        val stddev = value.getDouble(6)

        val summaryBytes = value.toString.getBytes.length

        totalDataSize.add(summaryBytes)

        /* Build message to send to Graphite */
        val sizeMetric = s"""${prefix}.summary.size ${size} ${time}"""
        val errorMetric = s"""${prefix}.summary.error ${error} ${time}"""
        val accuracyMetric = s"""${prefix}.summary.accuracy ${accuracy} ${time}"""
        val stddevMetric = s"""${prefix}.summary.stddev ${stddev} ${time}"""
        val summarySizeMetric = s"""${prefix}.dataSize.summaryBytes ${summaryBytes} ${time}"""

        /* Send data to Graphite */
        out.println(sizeMetric)
        out.flush
        out.println(errorMetric)
        out.flush
        out.println(accuracyMetric)
        out.flush
        out.println(stddevMetric)
        out.flush
        out.println(summarySizeMetric)
        out.flush
      }

      override def close(errorOrNull: Throwable) = {
        socket.close
      }
    }

    val metrics = windowedRecords
        .writeStream
        .foreach(writer)
        .outputMode("complete")
        .trigger(ProcessingTime(60.seconds))
        .start()


    /* Add listener to extract statistics */
    import org.apache.spark.sql.streaming.StreamingQueryListener
    import org.apache.spark.sql.streaming.StreamingQueryListener.{QueryProgressEvent, QueryStartedEvent, QueryTerminatedEvent}

    /* Create StreamingQueryListener */
    val queryListener: StreamingQueryListener = new StreamingQueryListener() {

	/* When posted: Right after StreamExecution has started running streaming batches */
        override def onQueryStarted(queryStarted: QueryStartedEvent): Unit = {
            println("Query started: " + queryStarted.id)
        }

	/* When posted: Right before StreamExecution finishes running streaming batches (due to a stop or an exception) */
        override def onQueryTerminated(queryTerminated: QueryTerminatedEvent): Unit = {
            println("Query terminated: " + queryTerminated.id)
        }

        /* When posted: ProgressReporter reports query progress (which is when StreamExecution runs batches and a trigger has finished) */
        override def onQueryProgress(queryProgress: QueryProgressEvent): Unit = {
//	    if(queryProgress.progress.sources(0).description.startsWith("KafkaSource")){
		val socket = new Socket(InetAddress.getByName("sky2.it.kth.se"), 2003)
		val out = new PrintStream(socket.getOutputStream)

		val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss")
		dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+0"))
		val parsedDate = dateFormat.parse(queryProgress.progress.timestamp)
		val time = (new java.sql.Timestamp(parsedDate.getTime()).getTime)/1000

		val numInputRows = s"""${prefix}.queryProgress.numInputRows ${queryProgress.progress.numInputRows} ${time}"""
		out.println(numInputRows)
		out.flush

		val inputRowsPerSecond = s"""${prefix}.queryProgress.inputRowsPerSecond ${queryProgress.progress.inputRowsPerSecond} ${time}"""
		out.println(inputRowsPerSecond)
		out.flush

		val processedRowsPerSecond = s"""${prefix}.queryProgress.processedRowsPerSecond ${queryProgress.progress.processedRowsPerSecond} ${time}"""
		out.println(processedRowsPerSecond)
		out.flush

		val totalData = totalDataSize.sum
                val totalDataSizeMetric = s"""${prefix}.dataSize.dataUntilNow ${totalData} ${time}"""
                out.println(totalDataSizeMetric)
                out.flush

		println(queryProgress.progress.numInputRows)
		println(queryProgress.progress.inputRowsPerSecond)
		println(queryProgress.progress.processedRowsPerSecond)
		   
		socket.close
//	    }
        }
    }

    /* Listener to monitor stages information */
    import org.apache.spark.scheduler._
    val stagesLogger = LogManager.getLogger("Stages")

    class CustomListener extends SparkListener  {

      override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit = {

        val socket = new Socket(InetAddress.getByName("sky2.it.kth.se"), 2003)
        val out = new PrintStream(socket.getOutputStream)

        /* Prepare data */
        val time = stageCompleted.stageInfo.completionTime.get/1000

	/* Get accumulables values */
        val accumulables = stageCompleted.stageInfo.accumulables

	for((k,v) <- accumulables){
	   /* Buil message to send to Graphite */
	   val metric = v.name.get.replaceAll("\\s+|[(),]+", "_")
	   val value = v.value.getOrElse(0)
	   val m = s"""${prefix}.stages.${metric} ${value} ${time}"""

	   /* Send data to Graphite */
	   out.println(m)
	   out.flush

//           logger.warn(s"Stage completed, m: ${m}")

	}

	socket.close
      }

      var startTimes = Map[Int, Long]()
      var totalDuration = 0L

      override def onJobStart(jobStart: SparkListenerJobStart): Unit = {	
	startTimes = startTimes + (jobStart.jobId -> jobStart.time)
      }

      override def onJobEnd(jobEnd: SparkListenerJobEnd): Unit = {
           val socket = new Socket(InetAddress.getByName("sky2.it.kth.se"), 2003)
           val out = new PrintStream(socket.getOutputStream)

	   val duration = jobEnd.time - startTimes.get(jobEnd.jobId).get
	   totalDuration = totalDuration + duration

           val mDuration = s"""${prefix}.jobs.duration ${duration} ${jobEnd.time/1000}"""
	   val mTotalDuration = s"""${prefix}.jobs.totalDuration ${totalDuration} ${jobEnd.time/1000}"""

	   out.println(mDuration)
	   out.flush
	   out.println(mTotalDuration)
	   out.flush

//           logger.warn(s"JobEnd: ${mDuration}")

           socket.close
      }
    }

    val accumulablesListener = new CustomListener
    spark.sparkContext.addSparkListener(accumulablesListener)
    spark.streams.addListener(queryListener)

/*
    /* Print on console */
    val toConsole = windowedRecords
        .orderBy($"window".desc)
        .writeStream
        .format("console")
        .option("truncate","true")
        .outputMode("complete")
        .trigger(ProcessingTime(60.seconds))
        .start()
    toConsole.awaitTermination
*/

    metrics.awaitTermination
    toKafka.awaitTermination

  }
}

object CreateDecentralizedMGSummaryTest extends UserDefinedAggregateFunction {

     var k = 0

     // Data types of input arguments of this aggregate function
     def inputSchema: StructType = new StructType()
	.add("error", DoubleType)
        .add("summary", ArrayType(new StructType().add("value", StringType).add("frequency", LongType).add("exactFrequency", LongType)))
	.add("clusterId", StringType)
        .add("currentBigWindow", new StructType().add("start", TimestampType).add("end", TimestampType))
	.add("inputWindow", new StructType().add("start", TimestampType).add("end", TimestampType))

     // Data types of values in the aggregation buffer
     def bufferSchema: StructType = new StructType()
	.add("partialSummary", ArrayType(new StructType().add("value", StringType).add("frequency", LongType).add("exactFrequency", LongType)))
	.add("clusters", ArrayType(StringType))
        .add("window", new StructType().add("start", TimestampType).add("end", TimestampType))
	.add("error", DoubleType)

     // The data type of the returned value
     def dataType = new StructType()
        .add("window", new StructType().add("start", TimestampType).add("end", TimestampType))
	.add("size", LongType)
	.add("error", DoubleType)
	.add("accuracy", DoubleType)
        .add("globalSummary", ArrayType(new StructType().add("value", StringType).add("frequency", LongType).add("exactFrequency", LongType)))
        .add("clusters", ArrayType(StringType))

     // Whether this function always returns the same output on the identical input
     def deterministic: Boolean = true

     // Initializes the given aggregation buffer. The buffer itself is a `Row` that in addition to
     // standard methods like retrieving a value at an index (e.g., get(), getBoolean()), provides
     // the opportunity to update its values. Note that arrays and maps inside the buffer are still
     // immutable.
     def initialize(buffer: MutableAggregationBuffer): Unit = {
        val prop = new Properties()
        prop.load(new FileInputStream("app.properties"))
        k = prop.getProperty("dec.k").toInt

	buffer(0) = Array()
	buffer(1) = Array()
	buffer(2) = (new Timestamp(0), new Timestamp(0))
	buffer(3) = 0.0
     }

     def update(buffer: MutableAggregationBuffer, input: Row): Unit = {	
	/* Check window times: set start time and end time only once at the beginning */
	if( !buffer.isNullAt(2) ) {
	   /* Take window currently stored into buffer */
	   val bufferWindowRow = buffer.getAs[Row](2)
           var bufferWindow = (bufferWindowRow.getAs[Timestamp](0), bufferWindowRow.getAs[Timestamp](1))
	   
	   /* If bufferWindow has not been set, set to the window taken from input: it's the reference window for computation */
	   if( bufferWindow._1.equals(new Timestamp(0)) && bufferWindow._2.equals(new Timestamp(0)) ){
	      val windowRow = input.getAs[Row](3)
	      bufferWindow = (windowRow.getAs[Timestamp](0), windowRow.getAs[Timestamp](1))
	      buffer(2) = (bufferWindow._1, bufferWindow._2)
	   }
	}
	   
	/* Window in the buffer must be set: update summary for each input windowed summary */
	if(!input.isNullAt(4) && !input.isNullAt(1) && !input.isNullAt(0)){
	   val currentWindowRow = input.getAs[Row](4)
           val currentWindow = (currentWindowRow.getAs[Timestamp](0), currentWindowRow.getAs[Timestamp](1))
           
	   val bufferWindowRow = buffer.getAs[Row](2)
           var bufferWindow = (bufferWindowRow.getAs[Timestamp](0), bufferWindowRow.getAs[Timestamp](1))

	   val t1 = currentWindow._1.compareTo(bufferWindow._1)
           val t2 = currentWindow._2.compareTo(bufferWindow._2)

	   /* If summary's window is included inside the reference window, update summary */
	   if( t1 >= 0 && t2 <= 0 ){

	      /* Update global summary by merging it with input */
  	      val globalSummary = buffer.getAs[Seq[Row]](0).map{ case Row(k: String, v: Long, exactV: Long) => (k, v, exactV) }
              val inputSummary = input.getAs[Seq[Row]](1).map{ case Row(k: String, v: Long, exactV: Long) => (k, v, exactV) }

	      val unionSummary = globalSummary ++ inputSummary

	      /* Sum counters of same key */
	      var updatedSummary = unionSummary.toList.groupBy(_._1)
		   .map{ case (k, list) => (k, list.map(_._2).sum, list.map(_._3).sum) }
	           .toList
	           .sortWith(_._2 > _._2)

	      /* Get final summary by applying MG merging algorithm */
	      val reducedList = updatedSummary.take(k+1) // take first k+1 elements: (k+1)-th is the minimum one
	      var minValue = 0L
	      if(updatedSummary.size > k)       // if updatedSummary has at least k+1 elments, you can decrement elements of reducedList, otherwise decrement 0
		minValue = reducedList.minBy(_._2)._2  // take minimum of counters: it is (k+1)-th element

              val summary = reducedList.map{ case (k, v, exactV) => (k, v-minValue, exactV)}
                .filter( (element) => element._2 > 0 ) // keep positive counters only (not exact counters)
                .toArray

              buffer(0) = summary

 	      /* Compute error: combineError + pruneError */
              val inputSumOfCounters = inputSummary.map(_._2).sum     // sum of counters in input summary
              val bufferSumOfCounters = globalSummary.map(_._2).sum   // sum of counters in current buffer
              val mergedSumOfCounters = summary.map(_._2).sum		// sum of counters in merged summary

	      val combineError: Double = (buffer.getDouble(3) + input.getDouble(0))	// sum of summary errors

              var pruneError = 0.0
//              if(updatedSummary.size >= k) // if size after merging is greater than k: prune => error introduced
//	        pruneError = (bufferSumOfCounters + inputSumOfCounters - mergedSumOfCounters).toDouble/(summary.size + 1).toDouble
//		pruneError = minValue

              val error: Double = combineError + pruneError

	      buffer(3) = error

              /* Update clusters involved */
	      if(!input.isNullAt(2))
	         buffer(1) = (buffer.getAs[Seq[String]](1).toArray ++ Array(input.getString(2)))
	    }

	 }
       
     }

     def merge(buffer1: MutableAggregationBuffer, buffer2: Row): Unit = {
	/* Update clusters involved */
	val clusters = (buffer1.getAs[Seq[String]](1) ++ buffer2.getAs[Seq[String]](1)).distinct
	buffer1(1) = clusters

	/* Merge two summaries */
        val sum1 = buffer1.getAs[Seq[Row]](0).map{case Row(k: String, v: Long, exactV: Long) => (k, v, exactV)}
        val sum2 = buffer2.getAs[Seq[Row]](0).map{case Row(k: String, v: Long, exactV: Long) => (k, v, exactV)}

	var buffer1SumOfCounters = 0L
	var buffer2SumOfCounters = 0L
	var combineError = 0.0

	var newSum = Seq[(String, Long, Long)]()
	if(sum1.size != 0 && sum2.size != 0)
	   if(sum1.toSet == sum2.toSet){
		newSum = sum1
        	combineError = buffer1.getDouble(3)
		buffer1SumOfCounters = sum1.map(_._2).sum
	    }
	   else{
		newSum = sum1 ++ sum2
		combineError = (buffer1.getDouble(3) + buffer2.getDouble(3))
                buffer1SumOfCounters = sum1.map(_._2).sum
                buffer2SumOfCounters = sum2.map(_._2).sum
	   }
	else if(sum1.size != 0 && sum2.size == 0){
	   newSum = sum1
	   combineError = buffer1.getDouble(3)
	   buffer1SumOfCounters = sum1.map(_._2).sum
	}
	else if(sum1.size == 0 && sum2.size != 0){
	   newSum = sum2
           combineError = buffer2.getDouble(3)
           buffer2SumOfCounters = sum2.map(_._2).sum
	}

        var finalSummary = newSum.toList.groupBy(_._1)
           .map { case (k, list)  => (k, list.map(_._2).sum, list.map(_._3).sum) }
           .toList
           .sortWith(_._2 > _._2)

        /* Get final summary by applying MG merging algorithm */
        val reducedList = finalSummary.take(k+1) // take first k+1 elements: (k+1)-th is the minimum one
        var minValue = 0L
        if(finalSummary.length > k)           // if finalSummary has more than k elments, you can decrement, otherwise decrement 0
           minValue = reducedList.minBy(_._2)._2  // take minimum of counters: it is (k+1)-th element

        val summary = reducedList.map{ case (k, v, exactV) => (k, v-minValue, exactV)}
                .filter( (element) => element._2 > 0 ) // keep positive counters only (not exact counters)
                .toArray

        buffer1(0) = summary

        /* Compute error: combineError + pruneError */
        val mergedSumOfCounters = summary.map(_._2).sum         // sum of counters in merged summary

        var pruneError = 0.0
        if(finalSummary.size > k) // if more than k elements: error introduced by pruning operation
//           pruneError = (buffer1SumOfCounters + buffer2SumOfCounters - mergedSumOfCounters).toDouble/(summary.size + 1).toDouble
	     pruneError = minValue

        val error: Double = combineError + pruneError

        buffer1(3) = error

	/* Check window boundaries */
	if( !buffer1.isNullAt(2) && !buffer2.isNullAt(2) ){
          val bufferWindowRow1 = buffer1.getAs[Row](2)
          var bufferWindow1 = (bufferWindowRow1.getAs[Timestamp](0), bufferWindowRow1.getAs[Timestamp](1))
          val bufferWindowRow2 = buffer2.getAs[Row](2)
          var bufferWindow2 = (bufferWindowRow2.getAs[Timestamp](0), bufferWindowRow2.getAs[Timestamp](1))

	  if( (!bufferWindow1._1.equals(new Timestamp(0))) && (!bufferWindow1._2.equals(new Timestamp(0))) && (!bufferWindow2._1.equals(new Timestamp(0))) && (!bufferWindow2._2.equals(new Timestamp(0)))  ){
	      buffer1(2) = (bufferWindow1._1, bufferWindow1._2)
	  }
	  else if( (!bufferWindow1._1.equals(new Timestamp(0))) && (!bufferWindow1._2.equals(new Timestamp(0))) && (bufferWindow2._1.equals(new Timestamp(0))) && (bufferWindow2._2.equals(new Timestamp(0))) ){
	     buffer1(2) = (bufferWindow1._1, bufferWindow1._2)
	  }
	  else {
	     buffer1(2) = (bufferWindow2._1, bufferWindow2._2)
 	  }
	}

     }
     

     // Calculates the final result
     def evaluate(buffer:Row): ((Timestamp, Timestamp), Long, Double, Double, Array[Tuple3[String,Long,Long]], Array[String]) = {

        /* 1) Get reference window */
        val bufferWindowRow = buffer.getAs[Row](2)
        val bufferWindow = (bufferWindowRow.getAs[Timestamp](0), bufferWindowRow.getAs[Timestamp](1))

        /* 5) Get computed summary */
        val globalSummary = buffer.getAs[Seq[Row]](0)
                .map{case Row(k: String, v: Long, exactV: Long) => (k, v, exactV)}
                .sortWith(_._2 > _._2)
                .toArray

        /* 2) Get summary size */
        val size = globalSummary.size

        /* 3) Get error */
        val error = buffer.getDouble(3)

        /* 4) Compute accuracy: fraction of elements in current size which satisfies error bounds */
		val trues = globalSummary.count{ case (k, counter, exactCounter) => (exactCounter >= counter) && (exactCounter <= counter + error)}

        var accuracy = 0.0
        if(globalSummary.size != 0) accuracy = trues.toDouble/globalSummary.size.toDouble

		/* 6) Get clusters involved in summary */
		val clusters = buffer.getAs[Seq[String]](1).toArray

		(bufferWindow, size, error, accuracy, globalSummary, clusters)
    }
}

object CreateDecentralizedMGSummaryNotTest extends UserDefinedAggregateFunction {

     val n = 30

     // Data types of input arguments of this aggregate function
     def inputSchema: StructType = new StructType()
	.add("currentTime", TimestampType)
        .add("summary", ArrayType(new StructType().add("value", StringType).add("frequency", LongType)))
	.add("clusterId", StringType)
        .add("currentBigWindow", new StructType().add("start", TimestampType).add("end", TimestampType))
	.add("inputWindow", new StructType().add("start", TimestampType).add("end", TimestampType))

     // Data types of values in the aggregation buffer
     def bufferSchema: StructType = new StructType()
	.add("partialSummary", ArrayType(new StructType().add("value", StringType).add("frequency", LongType)))
	.add("clusters", ArrayType(StringType))
        .add("window", new StructType().add("start", TimestampType).add("end", TimestampType))

     // The data type of the returned value
     def dataType = new StructType()
//        .add("window", new StructType().add("start", TimestampType).add("end", TimestampType))
        .add("globalSummary", ArrayType(new StructType().add("value", StringType).add("frequency", LongType)))
        .add("clusters", ArrayType(StringType))
        .add("window", new StructType().add("start", TimestampType).add("end", TimestampType))

     // Whether this function always returns the same output on the identical input
     def deterministic: Boolean = true

     // Initializes the given aggregation buffer. The buffer itself is a `Row` that in addition to
     // standard methods like retrieving a value at an index (e.g., get(), getBoolean()), provides
     // the opportunity to update its values. Note that arrays and maps inside the buffer are still
     // immutable.
     def initialize(buffer: MutableAggregationBuffer): Unit = {
	buffer(0) = Array()
	buffer(1) = Array()
	buffer(2) = (new Timestamp(0), new Timestamp(0))
     }

     def update(buffer: MutableAggregationBuffer, input: Row): Unit = {	
	/* Check window times: set start time and end time only once at the beginning */
	if( !buffer.isNullAt(2) ) {
	   /* Take window currently stored into buffer */
	   val bufferWindowRow = buffer.getAs[Row](2)
           var bufferWindow = (bufferWindowRow.getAs[Timestamp](0), bufferWindowRow.getAs[Timestamp](1))
	   
	   /* Check if bufferWindow has not been set, otherwise set to the window taken from input */
	   if( bufferWindow._1.equals(new Timestamp(0)) && bufferWindow._2.equals(new Timestamp(0)) ){
	      val windowRow = input.getAs[Row](3)
	      bufferWindow = (windowRow.getAs[Timestamp](0), windowRow.getAs[Timestamp](1))
	      buffer(2) = (bufferWindow._1, bufferWindow._2)

	   }
	}
	   
	   /* Window in the buffer must be set: update summary for each input windowed summary */ 
	   if(!input.isNullAt(4)){
	      val currentWindowRow = input.getAs[Row](4)
              val currentWindow = (currentWindowRow.getAs[Timestamp](0), currentWindowRow.getAs[Timestamp](1))

//	      if(currentWindow._1.after(bufferWindow._1) && currentWindow._2.before(bufferWindow._2) ){

		 /* Update summary merging them */
	         val globalSummary = buffer.getAs[Seq[Row]](0).map{case Row(k: String, v: Long) => (k, v)}
        	 val inputSummary = input.getAs[Seq[Row]](1).map{case Row(k: String, v: Long) => (k, v)}
		 
		 /* Append second summary to the first one */
	         val unionSummary = globalSummary ++ inputSummary

		 /* Sum counters of same key */
	         var updatedSummary = unionSummary.toList.groupBy(_._1)
	           .map { case (k, v) => (k, sum(v.map(_._2))) }
	           .toList
	           .sortWith(_._2 > _._2)

		 /* Get final summary by applying MG merging algorithm */
	         val reducedList = updatedSummary.take(n+1) // take first n+1 elements: (n+1)-th is the minimum one
        	 var minValue = 0L
	         if(reducedList.length >= (n+1))
        	    minValue = reducedList.minBy(_._2)._2 // take minimum of counters: value of counter of last element is (n+1)-th

	         var map = reducedList.toMap
        	 for( (k: String, v: Long) <- map)
	            map = map + (k -> (v-minValue).toLong) // Subtract (n+1)-th element from all counters

	         buffer(0) = map.filter( (element) => element._2 > 0 ).toArray // Keep positive counters only

//	         buffer(0) = finalSummary

        	 /* Update clusters involved */
	         if(!input.isNullAt(2))
	           buffer(1) = (buffer.getAs[Seq[String]](1).toArray ++ Array(input.getString(2)))	        

//	      }
	   }	
       
     }

     def sum(xs: List[Long]): Long = {
	xs match {
           case x :: tail => x + sum(tail) // if there is an element, add it to the sum of the tail
           case Nil => 0 // if there are no elements, then the sum is 0
        }
     }

     def merge(buffer1: MutableAggregationBuffer, buffer2: Row): Unit = {
	/* Update clusters involved */
	val clusters = (buffer1.getAs[Seq[String]](1) ++ buffer2.getAs[Seq[String]](1)).distinct
	buffer1(1) = clusters

	/* Merge two summaries */
        val sum1 = buffer1.getAs[Seq[Row]](0).map{case Row(k: String, v: Long) => (k, v)}
        val sum2 = buffer2.getAs[Seq[Row]](0).map{case Row(k: String, v: Long) => (k, v)}
        val newSum = sum1 ++ sum2

        var finalSummary = newSum.toList.groupBy(_._1)
           .map { case (k, v) => (k, sum(v.map(_._2))) }
           .toList
           .sortWith(_._2 > _._2)

        buffer1(0) = finalSummary.take(n)

	/* Check window boundaries */
	if( !buffer1.isNullAt(2) && !buffer2.isNullAt(2) ){
          val bufferWindowRow1 = buffer1.getAs[Row](2)
          var bufferWindow1 = (bufferWindowRow1.getAs[Timestamp](0), bufferWindowRow1.getAs[Timestamp](1))
          val bufferWindowRow2 = buffer2.getAs[Row](2)
          var bufferWindow2 = (bufferWindowRow2.getAs[Timestamp](0), bufferWindowRow2.getAs[Timestamp](1))

	  if( (!bufferWindow1._1.equals(new Timestamp(0))) && (!bufferWindow1._2.equals(new Timestamp(0))) && (!bufferWindow2._1.equals(new Timestamp(0))) && (!bufferWindow2._2.equals(new Timestamp(0)))  ){
	      buffer1(2) = (bufferWindow1._1, bufferWindow1._2)
	  }
	  else if( (!bufferWindow1._1.equals(new Timestamp(0))) && (!bufferWindow1._2.equals(new Timestamp(0))) && (bufferWindow2._1.equals(new Timestamp(0))) && (bufferWindow2._2.equals(new Timestamp(0))) ){
	     buffer1(2) = (bufferWindow1._1, bufferWindow1._2)
	  }
	  else {
	     buffer1(2) = (bufferWindow2._1, bufferWindow2._2)
 	  }
	}
	
     }
     

     // Calculates the final result
     def evaluate(buffer:Row): (Array[Tuple2[String,Long]], Array[String], (Timestamp, Timestamp)) = {

	val globalSummary = buffer.getAs[Seq[Row]](0).map{case Row(k: String, v: Long) => (k, v)}.toArray

	val clusters = buffer.getAs[Seq[String]](1).toArray

        val bufferWindowRow = buffer.getAs[Row](2)
        val bufferWindow = (bufferWindowRow.getAs[Timestamp](0), bufferWindowRow.getAs[Timestamp](1))

	(globalSummary, clusters, bufferWindow)
     }
   
}

  
