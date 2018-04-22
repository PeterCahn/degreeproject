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

import java.util.Timer
import java.util.TimerTask

import org.apache.log4j._
import scala.concurrent.duration._

object DataProducer {

  def main(args: Array[String]) {

   BasicConfigurator.configure()

   /* Extract configuration from property file */
   val prop = new Properties()
   val in = new FileInputStream("app.properties")
   prop.load(in)

   val brokers = prop.getProperty("test.producer.brokers")
   val topic = prop.getProperty("test.producer.topic")

   val rowsPerSecond = prop.getProperty("test.producer.rowsPerSecond").toInt

   /* Set kafka configuration properties */
   val conf = new HashMap[String, Object]()
   conf.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
   conf.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
   conf.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")

   val producer = new KafkaProducer[String, String](conf)

   import org.apache.log4j.LogManager
   val LOG = Logger.getLogger("Producer")

   val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
   dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+1"))

   /* Send input data size to Graphite every minute */
   import java.net.Socket
   import java.io._
   import java.net._
   import scala.io._

   // Get the word stream from the source 
   import scala.io.Source

   /* Compute milliseconds and nanoseconds for waiting anc calibrate producing rate */
   val rate = (1000.toDouble / rowsPerSecond.toDouble).toString.split("\\.")
   val millis = rate(0).toInt
   val nanos = String.format("%1$-" + 6 + "s", rate(1)).replace(' ', '0').toInt // pad with zeros to transform in nanoseconds
//   val nanos =  String.format("%6d", rate(1).toString ).toInt
//   val nanos = rate(1).subString(0,5) toInt * 100 * 1000
   println("nanos: " + nanos)

   val filename = prop.getProperty("test.producer.sourceFile")
   println(filename)

   for (line <- Source.fromFile(filename).getLines) {
	
	   println(line)
          
           val words = line.split("[.,\\W+]")
	
	   words.foreach( word => {

		val line  = s"""${word}"""
	   	val msg = new ProducerRecord[String, String](topic, line)

		LOG.warn(s"line: ${line}")	
	
                producer.send(msg)

//		Thread.sleep(millis, nanos)

	   })

        }

  }
}
                         
