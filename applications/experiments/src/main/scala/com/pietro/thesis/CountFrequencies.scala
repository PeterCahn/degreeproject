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

object ComputeExactFrequencies {
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
	.select("value")

    /* Windowed computation: count all elements and sum them up */
    val windowedRecords = records
	.withColumn("timestamp", current_timestamp())
	.withWatermark("timestamp", 10 + " minute")
	.groupBy($"value")
	.agg(count($"value") as "exactFrequency")
	.select($"value", $"exactFrequency")
	.orderBy($"exactFrequency".desc)

    import org.apache.spark.sql.streaming.{OutputMode, Trigger, ProcessingTime}

    /* Produce to Kafka topic */
    val toKafka = windowedRecords
	.orderBy($"exactFrequency".desc)	
	.selectExpr("CAST(value AS STRING) AS key", "to_json(struct(*)) AS value")
	.writeStream
	.outputMode("complete")
    	.format("kafka")
	.option("kafka.bootstrap.servers", outputBroker)
    	.option("topic", outputTopic)
    	.option("checkpointLocation", "frequencies_complete_dir")
    	.start()

    val toConsole = windowedRecords	
	.orderBy($"exactFrequency".desc)
        .writeStream
        .format("console")
        .option("truncate","false")
        .outputMode("complete")
        .start()

    toConsole.awaitTermination
    toKafka.awaitTermination

  }


}
