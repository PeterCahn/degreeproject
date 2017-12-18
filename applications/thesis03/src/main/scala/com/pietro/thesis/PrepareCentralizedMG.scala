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

object PrepareCentralizedMGSummary {
  def main(args: Array[String]) {

    val prop = new Properties()
    prop.load(new FileInputStream("app.properties"))
    val clusterId = prop.getProperty("cluster.id")
    val clusterName = prop.getProperty("cluster.name")
    
    /* Input Kafka configuration (which topic read as input and from which server) */
    val inputTopics = prop.getProperty("centralized.input.topics")
    val inputBrokers = prop.getProperty("centralized.input.brokers")

    /* Output Kafka configuration (which topic to write on and from which server) */
    val outputTopic = prop.getProperty("centralized.output.topic")
    val outputBrokers = prop.getProperty("centralized.output.brokers")

    /* Sliding window parameters */
    val windowLength = prop.getProperty("centralized.windowlength.minutes")
    val slideInterval = Minutes(prop.getProperty("centralized.windowslide.minutes").toInt)

    val maxOffsetsPerTrigger = prop.getProperty("centralized.maxOffsetsPerTrigger")

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

    val records1 = spark
        .readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", inputBrokers.split(",")(0))
        .option("subscribe", inputTopics.split(",")(0))
        .option("startingOffsets", "earliest")
        .option("failOnDataLoss", false)
//	.option("maxOffsetsPerTrigger", maxOffsetsPerTrigger.split(",")(0).toInt)
        .load()
//	.selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)") // cast key and value as string to be read in the following

    val records2 = spark
        .readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", inputBrokers.split(",")(1))
        .option("subscribe", inputTopics.split(",")(1))
        .option("startingOffsets", "earliest")
        .option("failOnDataLoss", false)
//        .option("maxOffsetsPerTrigger", maxOffsetsPerTrigger.split(",")(1).toInt)
        .load()
//        .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)")
/*
    /* Extract 'value' according to provided schema and adding column with timestamp */
    val parsedRecords1 = records1
	.select(from_json($"value", schema) as "parsed")
        .select( unix_timestamp($"parsed.created_at", "yyyy-MM-dd'T'HH:mm:ss").cast("timestamp").alias("timestamp"), $"parsed.*")

    val parsedRecords2 = records2
        .select(from_json($"value", schema) as "parsed")
        .select( unix_timestamp($"parsed.created_at", "yyyy-MM-dd'T'HH:mm:ss").cast("timestamp").alias("timestamp"), $"parsed.*")
*/

    /* Produce to Kafka topic */
    val toKafka1 = records1
//	.selectExpr("CAST(window AS STRING) AS key", "to_json(struct(*)) AS value")
	.writeStream
	.outputMode("append")
//	.trigger(ProcessingTime(60.seconds))
    	.format("kafka")
	.option("kafka.bootstrap.servers", outputBrokers)
    	.option("topic", outputTopic)
    	.option("checkpointLocation", "sky1") // ComputeClusterLocal-StructuredStreaming
    	.start()

    val toKafka2 = records2
//        .selectExpr("CAST(window AS STRING) AS key", "to_json(struct(*)) AS value")
        .writeStream
        .outputMode("append")
//        .trigger(ProcessingTime(60.seconds))
        .format("kafka")
        .option("kafka.bootstrap.servers", outputBrokers)
        .option("topic", outputTopic)
        .option("checkpointLocation", "sky2") // ComputeClusterLocal-StructuredStreaming
	.start()

    toKafka1.awaitTermination
    toKafka2.awaitTermination

  }
}
  

