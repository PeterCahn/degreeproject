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

import org.apache.log4j._
import scala.concurrent.duration._

import org.apache.spark.sql.{Row, SparkSession, Column}
import org.apache.spark.sql.expressions.{MutableAggregationBuffer, UserDefinedAggregateFunction}
import org.apache.spark.sql.types._

import java.sql.Timestamp

import org.apache.spark.sql.types._
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, Complete}
import org.apache.spark.sql.execution.aggregate.ScalaUDAF

object ClusterLocalMGSummary {
  def main(args: Array[String]) {

    val prop = new Properties()
    prop.load(new FileInputStream("app.properties"))
    val clusterId = prop.getProperty("cluster.id")
    val clusterName = prop.getProperty("cluster.name")
    val summarySize = prop.getProperty("cluster.k").toInt

    val prefixMetrics = prop.getProperty("cluster.prefixMetrics") + ".customMetrics"
   
    val maxOffsetsPerTrigger = prop.getProperty("cluster.maxOffsetsPerTrigger")
 
    /* Input Kafka configuration (which topic read as input and from which server) */
    val inputTopic = prop.getProperty("cluster.input.topic")
    val inputBrokers = prop.getProperty("cluster.input.brokers")

    /* Output Kafka configuration (which topic to write on and from which server) */
    val outputTopic = prop.getProperty("cluster.output.topic")
    val outputBrokers = prop.getProperty("cluster.output.brokers")

    /* Sliding window parameters */
    val windowLength = prop.getProperty("cluster.windowlength.minutes")
    val slideInterval = Minutes(prop.getProperty("cluster.windowslide.minutes").toInt)

    /* Enable testing frequency accuracy */
    val test = prop.getProperty("test").toBoolean
    
    import org.apache.spark.sql._    
    import org.apache.spark.sql.Dataset
    import org.apache.spark.sql.streaming.StreamingQuery
    import org.apache.spark.sql.types._
    import org.apache.spark.sql.functions._
    import org.apache.spark.sql.functions.lit

    val spark = SparkSession
        .builder()
        .appName("SSComputeClusterLocal")
        .getOrCreate()
    
    spark.conf.set("spark.sql.streaming.metricsEnabled", "true")

    import spark.implicits._

    val schema = new StructType()
        .add("created_at", StringType)
        .add("value", StringType)
        .add("producer_id", StringType)

    val records = spark
        .readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", inputBrokers)
        .option("subscribe", inputTopic)
        .option("startingOffsets", "latest")
        .option("failOnDataLoss", false)
//	.option("maxOffsetsPerTrigger", maxOffsetsPerTrigger.toInt)
        .load()
	.selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)") // cast key and value as string to be read in the following

    /* Extract 'value' according to provided schema and adding column with timestamp */
    val parsedRecords = records
	.select(from_json($"value", schema) as "parsed")
        .select( unix_timestamp($"parsed.created_at", "yyyy-MM-dd'T'HH:mm:ss").cast("timestamp").alias("timestamp"), $"parsed.*")

    spark.udf.register("create_mg_summary", CreateMGSummary)

    lazy val summaryTest: UserDefinedAggregateFunction = CreateMGSummaryTest
    lazy val summaryNotTest: UserDefinedAggregateFunction = CreateMGSummaryNotTest
    lazy val summary = if(test) summaryTest else summaryNotTest

    /* Windowed computation: count all elements and sum them up */
    val windowedRecords = parsedRecords
	.withWatermark("timestamp", windowLength + " minute")
	.groupBy(window($"timestamp", windowLength + " minute") as "window")
	.agg(summary('value).as("MG summary"))
//	.filter($"window.end".as("timestamp") < current_timestamp()) // filter window starting now and ending 'windowLength' in the future

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
    mgs.add(DataTypes.createStructField("size", DataTypes.LongType, true))
    if(test) mgs.add(DataTypes.createStructField("clusterDistinctValues", DataTypes.LongType, true))
    if(test) mgs.add(DataTypes.createStructField("error", DataTypes.DoubleType, true))
    if(test) mgs.add(DataTypes.createStructField("correctValues", DataTypes.DoubleType, true))
    mgs.add(DataTypes.createStructField("summary", summaryStruct, true))

    val mgSchema = DataTypes.createStructType(mgs)

    val orderingTest = udf { list: WrappedArray[Row] => list.map(r => {(r.getString(0), r.getLong(1), r.getLong(2))}).sortWith(_._2 > _._2) }
    val orderingNotTest = udf { list: WrappedArray[Row] => list.map(r => (r.getString(0), r.getLong(1))).sortWith(_._2 > _._2)}
    val ordering = if(test) orderingTest else orderingNotTest

    val expandedRecords = windowedRecords	
        .select($"window", from_json($"MG summary", mgSchema) as "parsed" )
        .select($"window", $"parsed.*")
        .withColumn("clusterId", lit(clusterId))

    /* Show schema after computation */
    expandedRecords.printSchema()
   
    import org.apache.spark.sql.streaming.{OutputMode, Trigger, ProcessingTime}

    /* Produce to Kafka topic */
    val toKafka = expandedRecords
	.selectExpr("CAST(window AS STRING) AS key", "to_json(struct(*)) AS value")
	.writeStream
	.outputMode("append")
	.trigger(ProcessingTime(60.seconds))
    	.format("kafka")
	.option("kafka.bootstrap.servers", outputBrokers)
    	.option("topic", outputTopic)
    	.option("checkpointLocation", "ccl-ss") // ComputeClusterLocal-StructuredStreaming
    	.start()

    val toKafkaStreamingQueryName = toKafka.name

    /* Prepare to send metrics to Graphite */
    import java.net.Socket
    import java.io._
    import java.net._
    import scala.io._

    val totalDataSize = spark.sparkContext.longAccumulator("totalDataSize")
    val summaryBytes = spark.sparkContext.longAccumulator("summarybytes")
    val writer = new ForeachWriter[Row] {

      var socket: Socket = _
      var lastTime = 0L
      var maxSummarySize = 0
      var partition = 0L

      override def open(partitionId: Long, version: Long) = {
	socket = new Socket(InetAddress.getByName("sky2.it.kth.se"), 2003)
	maxSummarySize = summarySize
	partition = partitionId
        true
      }

      override def process(value: Row) = {

        val out = new PrintStream(socket.getOutputStream)

	/* Get data from Row */
        val time = (value.getAs[Row](0).getTimestamp(1).getTime)/1000
	lastTime = time
	val size = value.getLong(1)
	val error = value.getDouble(3)
//	val correctValues = value.getDouble(4)

	val thisSummaryBytes = value.toString.getBytes.length
	totalDataSize.add(thisSummaryBytes)
	summaryBytes.add(thisSummaryBytes)

	/* Build message to send to Graphite */
	val sizeMetric = s"""${prefixMetrics}.summary.size ${size} ${time}"""
	val errorMetric = s"""${prefixMetrics}.summary.error ${error} ${time}"""
//	val correctValuesMetric = s"""${prefixMetrics}.summary.correctValues ${correctValues} ${time}"""
//	val dataSizeMetric = s"""${prefixMetrics}.dataSize.${partition}.summaryBytes ${summaryBytes} ${time}"""

	/* Send data to Graphite */
	out.println(sizeMetric)
	out.flush
        out.println(errorMetric)
        out.flush
//      out.println(correctValuesMetric)
//      out.flush
//	out.println(dataSizeMetric)
//	out.flush

      }

      override def close(errorOrNull: Throwable) = {
	socket.close
      }
    }

    val metrics = expandedRecords
        .writeStream
        .foreach(writer)
        .outputMode("complete")
        .trigger(ProcessingTime(60.seconds))
        .start()

    /* Build StreamingQueryListener to add to the stream */
    import org.apache.spark.sql.streaming.StreamingQueryListener
    import org.apache.spark.sql.streaming.StreamingQueryListener.{QueryProgressEvent, QueryStartedEvent, QueryTerminatedEvent}

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
//	    if(queryProgress.progress.sources(0).description.startsWith("KakfaSource")){
		val socket = new Socket(InetAddress.getByName("sky2.it.kth.se"), 2003)
		val out = new PrintStream(socket.getOutputStream)

		val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss")
		dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+0"))
		val parsedDate = dateFormat.parse(queryProgress.progress.timestamp)
		val time = (new java.sql.Timestamp(parsedDate.getTime()).getTime)/1000

		val numInputRows = s"""${prefixMetrics}.queryProgress.numInputRows ${queryProgress.progress.numInputRows} ${time}"""
		out.println(numInputRows)
		out.flush

		val inputRowsPerSecond = s"""${prefixMetrics}.queryProgress.inputRowsPerSecond ${queryProgress.progress.inputRowsPerSecond} ${time}"""
		out.println(inputRowsPerSecond)
		out.flush

		val processedRowsPerSecond = s"""${prefixMetrics}.queryProgress.processedRowsPerSecond ${queryProgress.progress.processedRowsPerSecond} ${time}"""
		out.println(processedRowsPerSecond)
		out.flush

		val totalData = totalDataSize.value
	        val dataSizeMetric = s"""${prefixMetrics}.dataSize.dataUntilNow ${totalData} ${time}"""
		out.println(dataSizeMetric)
		out.flush

                val summary = summaryBytes.value
		if(summary > 0){
                  val mSummaryBytes = s"""${prefixMetrics}.dataSize.summaryBytes ${summary} ${time}"""
                  out.println(mSummaryBytes)
                  out.flush
		  summaryBytes.reset
                  println("summaryBytes: " + summary)
		}

		println(queryProgress.progress.numInputRows)
		println(queryProgress.progress.inputRowsPerSecond)
		println(queryProgress.progress.processedRowsPerSecond)
		println("dataSize: " + totalData)

		   
		socket.close
//	   }
        }
    }

    import org.apache.spark.scheduler._
    import org.apache.log4j.LogManager
    val logger = LogManager.getLogger("CustomListener")
 
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
	   val m = s"""${prefixMetrics}.stages.${metric} ${value} ${time}"""

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
	   totalDuration  = totalDuration + duration

           val mDuration = s"""${prefixMetrics}.jobs.duration ${duration} ${jobEnd.time/1000}"""
	   val mTotalDuration = s"""${prefixMetrics}.jobs.totalDuration ${totalDuration} ${jobEnd.time/1000}"""

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
    val toConsole = expandedRecords
        .orderBy($"window".desc)
        .withColumn("MGSummary", orderingTest($"summary").cast(mgSchema("summary").dataType))
        .drop($"summary")
        .writeStream
        .format("console")
        .option("truncate","true")
        .outputMode("complete")
        .trigger(ProcessingTime(60.seconds))
        .start()
*/

    toKafka.awaitTermination
//    toConsole.awaitTermination
    metrics.awaitTermination

  }
}
  
object CreateMGSummaryTest extends UserDefinedAggregateFunction {

     var k = 0

     // Data types of input arguments of this aggregate function
     def inputSchema: StructType = new StructType().add("element", StringType)

     // Data types of values in the aggregation buffer
     def bufferSchema: StructType = new StructType()
	.add("approxFrequencies", MapType(StringType, LongType))
	.add("realFrequencies", MapType(StringType, LongType))	
   
     // The data type of the returned value
     def dataType: DataType = StringType
   
     // Whether this function always returns the same output on the identical input
     def deterministic: Boolean = true

     // Initializes the given aggregation buffer. The buffer itself is a `Row` that in addition to
     // standard methods like retrieving a value at an index (e.g., get(), getBoolean()), provides
     // the opportunity to update its values. Note that arrays and maps inside the buffer are still
     // immutable.
     def initialize(buffer: MutableAggregationBuffer): Unit = {

	val prop = new Properties()
	prop.load(new FileInputStream("app.properties"))
	k = prop.getProperty("k").toInt

	buffer(0) = Map()
	buffer(1) = Map()
     }
   
     // Updates the given aggregation buffer `buffer` with new input data from `input`
     def update(buffer: MutableAggregationBuffer, input: Row): Unit = {
       if (!input.isNullAt(0)) {
   	   var summary = buffer.getAs[Map[String, Long]](0)
	   var exactMap = buffer.getAs[Map[String, Long]](1)
	   val keyToAdd = input.getString(0)
	   
	   if(summary.contains(keyToAdd)){
              /* The elements is already contained: increment the count */
	      val value = summary.get(keyToAdd).get + 1
	      summary = summary + (keyToAdd -> value)
	   } else if(summary.size < k)
              /* There is space to add the element in the summary */
	      summary = summary + (keyToAdd -> 1)
	   else {
	      /* There is no space to add the element: decrement all counters and delete null ones */
	      var m = Map[String, Long]()
	      for( (k: String, v: Long) <- summary)
		m = m + (k -> (v-1).toLong)	      
    	      
	      summary = m.filter( (element) => element._2 > 0 )
	   }

  	   if(exactMap.contains(keyToAdd)){
              /* The elements is already contained: increment the count */
              val exactValue = exactMap.get(keyToAdd).get + 1
              exactMap = exactMap + (keyToAdd -> exactValue)
           } else
              exactMap = exactMap + (keyToAdd -> 1)

	   buffer(0) = summary
	   buffer(1) = exactMap
	}
     }
     
     def sum(xs: List[Long]): Long = {
       xs match {
         case x :: tail => x + sum(tail) // if there is an element, add it to the sum of the tail
         case Nil => 0 // if there are no elements, then the sum is 0
       }
     }
 
     // Merges two aggregation buffers and stores the updated buffer values back to `buffer1`
     def merge(buffer1: MutableAggregationBuffer, buffer2: Row): Unit = {

	/* Merge two MG summaries */
	var list = List[(String, Long)]()
	if (!buffer2.isNullAt(0) && !buffer1.isNullAt(0)){
   	   list = buffer1.getAs[Map[String, Long]](0).toList ::: buffer2.getAs[Map[String, Long]](0).toList
	}	

	if(list.length > 0){
	   var sortedList = list.groupBy(_._1)
	      .map { case (k,v) => (k, sum(v.map(_._2))) }
	      .toList
	      .sortWith(_._2 > _._2)

	   buffer1(0) = sortedList.take(k).toMap
	}

        var exactList = List[(String, Long)]()
//	if(test) {
           /* Merge two exact summaries */    
           if (!buffer2.isNullAt(1) && !buffer1.isNullAt(1)){
              exactList = buffer1.getAs[Map[String, Long]](1).toList ::: buffer2.getAs[Map[String, Long]](1).toList
           }
   
           if(exactList.length > 0){
              var exactSortedList = exactList.groupBy(_._1)
                 .map { case (k,v) => (k, sum(v.map(_._2))) }
                 .toList
                 .sortWith(_._2 > _._2)
   
              buffer1(1) = exactSortedList.toMap
           }
//	}
     }

     // Calculates the final result
     def evaluate(buffer: Row): String = {
	
	val map = buffer.getAs[Map[String, Long]](0).toSeq.sortWith(_._2 > _._2).toMap
	val exactFrequencies = buffer.getAs[Map[String, Long]](1).toSeq.sortWith(_._2 > _._2).toMap
	
	var summary = new org.json.JSONArray()

        val sumOfCounters = map.foldLeft(0L){ case (a: Long, (k: String, v: Long)) => a + v }
	val exactSumOfCounters = exactFrequencies.foldLeft(0L){ case (a: Long, (k: String, v: Long)) => a + v }
        val error = (exactSumOfCounters - sumOfCounters).toDouble/(map.size+1).toDouble

	var trues = 0

	for( (key, value) <- map) {
	   
	   val element = new org.json.JSONObject()
           val exactFrequency = exactFrequencies.get(key).getOrElse(0L)

	   element.put("value", key)
	   element.put("frequency", value)
	   element.put("exactFrequency", exactFrequency)
	   
	   summary.put(element)
	
           /* Count values in summary that satisfy disequality */	   
	   if( (exactFrequency >= value) && (exactFrequency <= value + error) )
	      trues = trues + 1
	}

	/* Compute accuracy as fraction of elements in current size which satisfies error bounds */	
 	var accuracy = 0.0
	if(map.size != 0) accuracy = trues.toDouble/map.size.toDouble

        val mgJson = new org.json.JSONObject()
	mgJson.put("size", map.size)
	   .put("clusterDistinctValues", exactFrequencies.size)
	   .put("error", error)
	   .put("correctValues", accuracy)
	   .put("summary", summary)

	mgJson.toString
     }
}

object CreateMGSummaryNotTest extends UserDefinedAggregateFunction {

     var k = 0

     // Data types of input arguments of this aggregate function
     def inputSchema: StructType = new StructType().add("element", StringType)

     // Data types of values in the aggregation buffer
     def bufferSchema: StructType = new StructType()
	.add("approxFrequencies", MapType(StringType, LongType))
   
     // The data type of the returned value
     def dataType: DataType = StringType
   
     // Whether this function always returns the same output on the identical input
     def deterministic: Boolean = true
     // standard methods like retrieving a value at an index (e.g., get(), getBoolean()), provides
     // the opportunity to update its values. Note that arrays and maps inside the buffer are still
     // immutable.
     def initialize(buffer: MutableAggregationBuffer): Unit = {
        val prop = new Properties()
        prop.load(new FileInputStream("app.properties"))
        k = prop.getProperty("k").toInt

	buffer(0) = Map()
     }
   
     // Updates the given aggregation buffer `buffer` with new input data from `input`
     def update(buffer: MutableAggregationBuffer, input: Row): Unit = {
       if (!input.isNullAt(0)) {
   	   var summary = buffer.getAs[Map[String, Long]](0)
	   val keyToAdd = input.getString(0)	   
   	   
	   if(summary.contains(keyToAdd)){
              /* The elements is already contained: increment the count */
	      val value = summary.get(keyToAdd).get + 1
	      summary = summary + (keyToAdd -> value)
	   } else if(summary.size < k)
              /* There is space to add the element in the summary */
	      summary = summary + (keyToAdd -> 1)
	   else {
	      /* There is no space to add the element: decrement all counters and delete null ones */
	      var m = Map[String, Long]()
	      for( (k: String, v: Long) <- summary)
		m = m + (k -> (v-1).toLong)	      
    	      
	      summary = m.filter( (element) => element._2 > 0 )
	   }

	   buffer(0) = summary
       }
     }
     
     def sum(xs: List[Long]): Long = {
       xs match {
         case x :: tail => x + sum(tail) // if there is an element, add it to the sum of the tail
         case Nil => 0 // if there are no elements, then the sum is 0
       }
     }
 
     // Merges two aggregation buffers and stores the updated buffer values back to `buffer1`
     def merge(buffer1: MutableAggregationBuffer, buffer2: Row): Unit = {

	/* Merge two MG summaries */
	var list = List[(String, Long)]()
	if (!buffer2.isNullAt(0) && !buffer1.isNullAt(0)){
   	   list = buffer1.getAs[Map[String, Long]](0).toList ::: buffer2.getAs[Map[String, Long]](0).toList
	}	

	if(list.length > 0){
	   var sortedList = list.groupBy(_._1)
	      .map { case (k,v) => (k, sum(v.map(_._2))) }
	      .toList
	      .sortWith(_._2 > _._2)

	   buffer1(0) = sortedList.take(k).toMap
	}
     }

     // Calculates the final result
     def evaluate(buffer: Row): String = {
	
	val map = buffer.getAs[Map[String, Long]](0).toSeq.sortWith(_._2 > _._2).toMap	
	var summary = new org.json.JSONArray()

	for( (k,v) <- map) {
	   val element = new org.json.JSONObject()	
	   element.put("value", k)
	   element.put("frequency", v)
	   summary.put(element)
	}

        val mgJson = new org.json.JSONObject()
	mgJson.put("size", map.size)
	   .put("summary", summary)
	   .toString		
     }   
}


