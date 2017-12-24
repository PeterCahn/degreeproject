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

object DecentralizedAVG {
  def main(args: Array[String]) {

    val prop = new Properties()
    prop.load(new FileInputStream("app.properties"))
//    val clusterId = prop.getProperty("dec.id")
//    val clusterName = prop.getProperty("dec.name")
    val summarySize = prop.getProperty("dec.k").toInt

    val prefixMetrics = prop.getProperty("dec.prefixMetrics") + ".customMetrics-avg"

    val maxOffsetsPerTrigger = prop.getProperty("dec.maxOffsetsPerTrigger")

    /* Input Kafka configuration (which topic read as input and from which server) */
    val inputTopic = prop.getProperty("dec.input.topic")
    val inputBrokers = prop.getProperty("dec.input.brokers")

    /* Output Kafka configuration (which topic to write on and from which server) */
    val outputTopic = prop.getProperty("dec.output.topic")
    val outputBrokers = prop.getProperty("dec.output.brokers")

    /* Sliding window parameters */
    val windowLength = prop.getProperty("dec.windowlength.minutes")
    val slideInterval = prop.getProperty("dec.windowslide.minutes")
    
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
	.add("window", new StructType().add("start", TimestampType).add("end", TimestampType))
	.add("count", LongType)
	.add("sum", LongType)
	.add("average", DoubleType)
	.add("clusterId",StringType)

    val records = spark
        .readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", inputBrokers)
        .option("subscribe", inputTopic)
        .option("startingOffsets", "latest")
        .load()
        .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)") // cast key and value as string to be read in the following

    /* Extract 'value' according to provided schema and adding column with timestamp */
    val parsedRecords = records
	.select(from_json($"value", schema) as "parsed")
        .select($"parsed.*")

    /* Windowed computation: count all elements and sum them up */
    val windowedRecords = parsedRecords
	.withColumn("windowEnd", $"window.end")
	.withColumn("windowStart", $"window.start")
        .groupBy(window($"windowStart", windowLength+" minutes", slideInterval+" minute") as "window")
	.agg(sum("sum") as "aggregateSum", sum("count") as "aggregateCount", avg("average") as "aggregateAvg")
        .filter($"window.end".as("timestamp") <= current_timestamp()) // filter window starting now and ending 'windowLength' in the future

    import org.apache.spark.sql.streaming.{OutputMode, Trigger, ProcessingTime}

    /* Produce to Kafka topic */
    val toKafka = windowedRecords
	.selectExpr("CAST(window AS STRING) AS key", "to_json(struct(*)) AS value")
	.writeStream
	.outputMode("update")
	.trigger(ProcessingTime(60.seconds))
    	.format("kafka")
	.option("kafka.bootstrap.servers", outputBrokers)
    	.option("topic", outputTopic)
    	.option("checkpointLocation", "dec-avg") // ComputeClusterLocal-StructuredStreaming
    	.start()

    val toKafkaStreamingQueryName = toKafka.name

    /* Prepare to send metrics to Graphite */
    import java.net.Socket
    import java.io._
    import java.net._
    import scala.io._

    val totalDataSize = spark.sparkContext.longAccumulator("totalDataSize")
    val totalInputDataSize = spark.sparkContext.longAccumulator("totalInputDataSize")
    val summaryBytes = spark.sparkContext.longAccumulator("summaryBytes")
    val inputSummaryBytes = spark.sparkContext.longAccumulator("inputSummaryBytes")

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

	val count = value.getLong(1)
	val sum = value.getLong(2)
	val avg = value.getDouble(3)

	val thisSummaryBytes = value.toString.getBytes.length
	totalDataSize.add(thisSummaryBytes)
	summaryBytes.add(thisSummaryBytes)

	/* Build message to send to Graphite */
	val mCount = s"""${prefixMetrics}.summary.count ${count} ${time}"""
	val mSum= s"""${prefixMetrics}.summary.sum ${sum} ${time}"""
	val mAvg = s"""${prefixMetrics}.summary.average ${avg} ${time}"""

	/* Send data to Graphite */
	out.println(mCount)
	out.flush
        out.println(mSum)
        out.flush
        out.println(mAvg)
        out.flush

      }

      override def close(errorOrNull: Throwable) = {
	socket.close
      }
    }

    val inputDataSize = new ForeachWriter[Row] {

      override def open(partitionId: Long, version: Long) = { true }

      override def process(value: Row) = {
        /* Get data from Row */
        val thisSummaryBytes = value.toString.getBytes.length
        totalInputDataSize.add(thisSummaryBytes)
        inputSummaryBytes.add(thisSummaryBytes)
      }

      override def close(errorOrNull: Throwable) = { }

    }

    val outputMetrics = windowedRecords
        .writeStream
        .foreach(writer)
        .outputMode("update")
        .trigger(ProcessingTime(60.seconds))
        .start()

    val inputMetrics = parsedRecords
        .writeStream
        .foreach(inputDataSize)
        .outputMode("append")
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

                val totalData = totalDataSize.value
                val inputTotalData = totalInputDataSize.value

		var metricsLong = Map[String, Long]()
		var metricsDouble = Map[String, Double]()

		metricsLong += ("queryProgress.numInputRows" -> queryProgress.progress.numInputRows)
		metricsLong += ("dataSize.dataUntilNow" -> totalData)
		metricsLong += ("dataSize.inputDataUntilNow" -> inputTotalData)

                metricsDouble += ("queryProgress.inputRowsPerSecond" -> queryProgress.progress.inputRowsPerSecond)
                metricsDouble += ("queryProgress.processedRowsPerSecond" -> queryProgress.progress.processedRowsPerSecond)

		for((metric, value) <- metricsLong){
		   val m = s"""${prefixMetrics}.${metric} ${value} ${time}"""
		   out.println(m)
		   out.flush

		   println(metric + "=> " + value)
		}

                for((metric, value) <- metricsDouble){
                   val m = s"""${prefixMetrics}.${metric} ${value} ${time}"""
                   out.println(m)
                   out.flush

                   println(metric + "=> " + value)
                }

                val summary = summaryBytes.value
		if(summary > 0){
                  val mSummaryBytes = s"""${prefixMetrics}.dataSize.summaryBytes ${summary} ${time}"""
                  out.println(mSummaryBytes)
                  out.flush
		  summaryBytes.reset
                  println("summaryBytes: " + summary)
		}

                val inputSummary = inputSummaryBytes.value
                if(inputSummary > 0){
                  val mSummaryBytes = s"""${prefixMetrics}.dataSize.inputSummaryBytes ${inputSummary} ${time}"""
                  out.println(mSummaryBytes)
                  out.flush
                  inputSummaryBytes.reset
                  println("inputSummaryBytes: " + inputSummary)
                }

		println(" ")
		   
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
    val toConsole = windowedRecords
        .orderBy($"window".desc)
//        .withColumn("MGSummary", orderingTest($"summary").cast(mgSchema("summary").dataType))
//        .drop($"summary")
        .writeStream
        .format("console")
        .option("truncate","false")
        .outputMode("complete")
        .trigger(ProcessingTime(60.seconds))
        .start()
    toConsole.awaitTermination
*/

    toKafka.awaitTermination
    outputMetrics.awaitTermination
    inputMetrics.awaitTermination

  }
}
    

