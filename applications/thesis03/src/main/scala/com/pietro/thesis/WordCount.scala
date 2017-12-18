package com.pietro.thesis

import java.util.HashMap
import scala.io.Source
import scala.collection.immutable.StringOps
import scala.util.control.Exception._
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Calendar
import scala.util.Random
import scala.util.Try

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream._
import org.apache.spark.streaming.kafka010._
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.clients.producer._
import org.apache.spark.rdd.RDD
import java.util.Properties
import java.io.FileInputStream
import java.io.FileOutputStream
import org.json._

import org.apache.log4j._
import scala.concurrent.duration._

object WCProducer {

  def main(args: Array[String]) {

   BasicConfigurator.configure()

   /* Extract configuration from property file */
   val prop = new Properties()
   val in = new FileInputStream("app.properties")
   prop.load(in)
   val brokers = prop.getProperty("producer.brokers")
   val topic = prop.getProperty("producer.topic")
   in.close

   /* Set kafka configuration properties */
   val conf = new HashMap[String, Object]()
   conf.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
   conf.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
   conf.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")

   val producer = new KafkaProducer[String, String](conf)

   val LOG = Logger.getLogger("Producer")
   val producerId = "1"

   val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
   dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+1"))

   // Get the word stream from the source 
   import scala.io.Source

   val producerProp = new Properties()
   val in2 = new FileInputStream("producer.properties")
   producerProp.load(in2)
   var offset = producerProp.getProperty("producer.offset").toInt
   var count = 0L
   val filename = "data/reviews_Books.json"

   for (line <- Source.fromFile(filename).getLines.drop(offset)) {
           val json = new org.json.JSONObject(line)
           val words = json.getString("reviewText").split("[.,\\W+]")
	
	   offset = offset + 1

	   words.filter(_.length != 0).foreach( word => {
		val timestampInMs = Calendar.getInstance().getTimeInMillis()
           	val created_at = dateFormat.format(timestampInMs)

		val line  = s"""{"created_at":"${created_at}","value":"#${word}", "producer_id":"${producerId}"}"""
	   	val msg = new ProducerRecord[String, String](topic, line)

		count = count + 1
                producer.send(msg)

		Thread.sleep(1)

	   })

	   /* Store offset to avoid to  */
           val out = new FileOutputStream("producer.properties")
           producerProp.setProperty("producer.offset", offset.toString)
           producerProp.store(out, null)
           out.close()
        }

  }
}
                         
