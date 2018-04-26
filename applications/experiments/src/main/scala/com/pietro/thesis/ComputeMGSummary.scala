package com.pietro.thesis

// scalastyle:off println
import scala.io.Source
import scala.util.Random
import scala.util._

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}

import org.apache.spark.{SparkConf, SparkEnv, SparkContext}
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream._
import org.apache.spark.rdd.RDD
import java.util.Properties
import java.io.FileInputStream
import scala.collection.mutable.WrappedArray

import org.apache.spark.sql.{Row, SparkSession, Column}
import org.apache.spark.sql.expressions.{MutableAggregationBuffer, UserDefinedAggregateFunction}
import org.apache.spark.sql.types._

import java.sql.Timestamp

import org.apache.spark.sql.types._
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, Complete}
import org.apache.spark.sql.execution.aggregate.ScalaUDAF

object ComputeMGSummary {
  def main(args: Array[String]) {

    val prop = new Properties()
    prop.load(new FileInputStream("app.properties"))
    val clusterId = prop.getProperty("cluster.id")
    val clusterName = prop.getProperty("cluster.name")
  
    val inputKafkaTopic = prop.getProperty("test.input.topic")
    val inputKafkaBroker = prop.getProperty("test.input.brokers")

    val outputTopic = prop.getProperty("test.output.topic")
    val outputBroker = prop.getProperty("test.output.brokers")
 
    /* Sliding window parameters */
    val windowLength = prop.getProperty("test.windowlength.seconds")
    val slideInterval = Minutes(prop.getProperty("test.windowslide.seconds").toInt)

    val maxOffsetsPerTrigger = prop.getProperty("test.maxOffsetsPerTrigger")

    import org.apache.spark.sql._    
    import org.apache.spark.sql.Dataset
    import org.apache.spark.sql.streaming.StreamingQuery
    import org.apache.spark.sql.types._
    import org.apache.spark.sql.functions._
    import org.apache.spark.sql.functions.lit

    val spark = SparkSession
        .builder()
        .appName("ComputeExactFrequencies")
        .getOrCreate()
    
    import spark.implicits._

    val records = spark
	.readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", inputKafkaBroker)
        .option("subscribe", inputKafkaTopic)
        .option("startingOffsets", "earliest")
	.option("failOnDataLoss", "false")
	.option("maxOffsetsPerTrigger", maxOffsetsPerTrigger.toInt)
        .load()
        .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)") // cast key and value as string to be read in the following
	.filter($"value" > "")
	.select("value")

    spark.udf.register("create_mg_summary", CreateMGSummaryTest)

    lazy val summaryTest: UserDefinedAggregateFunction = CreateMGSummaryTest
    lazy val summary = summaryTest
	
    /* Windowed computation: count all elements and sum them up */
    val windowedRecords = records
	.withColumn("timestamp", current_timestamp())
	.withWatermark("timestamp", 10 + " minute")
	.groupBy(window($"timestamp", windowLength + " seconds") as "window")
	.agg(summary('value).as("MGSummary"))
//	.orderBy($"window".desc)

    import org.apache.spark.sql.types.DataTypes
    import scala.collection.mutable.ListBuffer
    import java.util.ArrayList

    /* Create schema for summary field in MGSummary */
    val summaryFields = new ArrayList[StructField]()
    summaryFields.add(DataTypes.createStructField("value", DataTypes.StringType, true))
    summaryFields.add(DataTypes.createStructField("frequency", DataTypes.LongType, true))
    summaryFields.add(DataTypes.createStructField("exactFrequency", DataTypes.LongType, true))
    val summaryStruct = DataTypes.createArrayType(DataTypes.createStructType(summaryFields))

    /* Create schema for MGSummary*/ 
    val mgs = new ArrayList[StructField]()
    mgs.add(DataTypes.createStructField("size", DataTypes.LongType, true))
    mgs.add(DataTypes.createStructField("clusterDistinctValues", DataTypes.LongType, true))
    mgs.add(DataTypes.createStructField("error", DataTypes.DoubleType, true))
    mgs.add(DataTypes.createStructField("truePositives", DataTypes.DoubleType, true))
    mgs.add(DataTypes.createStructField("summary", summaryStruct, true))

    val mgSchema = DataTypes.createStructType(mgs)

    val orderingTest = udf { list: WrappedArray[Row] => list.map(r => {(r.getString(0), r.getLong(1), r.getLong(2))}).sortWith(_._2 > _._2) }

    val expandedRecords = windowedRecords	
        .select($"window", from_json($"MGSummary", mgSchema) as "parsed" )
        .select($"window", $"parsed.*")	
        .withColumn("clusterId", lit(clusterId))

    /* Show schema after computation */
    expandedRecords.printSchema()
   
    import org.apache.spark.sql.streaming.{OutputMode, Trigger, ProcessingTime}

    /* Produce to Kafka topic */
    val toKafka = expandedRecords
//	.orderBy($"exactFrequency".desc)	
	.selectExpr("CAST(window AS STRING) AS key", "to_json(struct(*)) AS value")
	.writeStream
	.outputMode("complete")
//	.trigger(ProcessingTime("60 seconds"))
    	.format("kafka")
	.option("kafka.bootstrap.servers", outputBroker)
    	.option("topic", outputTopic)
    	.option("checkpointLocation", "frequencies_complete_dir")
    	.start()

    val toConsole = expandedRecords	
//	.orderBy($"exactFrequency".desc)
	.withColumn("MGSummary", orderingTest($"summary").cast(mgSchema("summary").dataType))
        .drop($"summary")
        .writeStream
        .format("console")
        .option("truncate","false")
        .outputMode("complete")
//        .trigger(ProcessingTime("5 seconds"))
        .start()

    toConsole.awaitTermination
//    toKafka.awaitTermination

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

	import scala.collection.mutable.ArrayBuffer

	val filename = "frequencies_input1.txt"
	trues = 0
	var index = 0

        var freqs = ArrayBuffer[Tuple2[String, Long]]()
	for (line <- Source.fromFile(filename).getLines) {

	   val j = new org.json.JSONObject(line)
	   val value: String  = j.getString("value")
	   val freq: Long = j.getLong("exactFrequency")

	   freqs += Tuple2[String, Long](value, freq)

        }

	freqs.sortWith(_._2 > _._2)

	for (element <- freqs){
	 
	   if(map.contains(element._1) && index < k)	    {
                 trues = trues + 1
                 index = index + 1
            }
	}

        /* Compute accuracy as fraction of elements in current size which satisfies error bounds */
        var accuracy = 0.0
        if(map.size != 0) accuracy = trues.toDouble/map.size.toDouble

        val mgJson = new org.json.JSONObject()
	mgJson.put("size", map.size)
	   .put("clusterDistinctValues", exactFrequencies.size)
	   .put("error", error)
	   .put("truePositives", accuracy)
	   .put("summary", summary)

	mgJson.toString
     }
}

}
