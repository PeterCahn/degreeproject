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

object WCProducer {

  def main(args: Array[String]) {

   BasicConfigurator.configure()

   /* Extract configuration from property file */
   val prop = new Properties()
   val in = new FileInputStream("app.properties")
   prop.load(in)
   val brokers = prop.getProperty("producer.brokers")
   val topic = prop.getProperty("producer.topic")

   val rowsPerSecond = prop.getProperty("producer.rowsPerSecond").toInt

   /* Set kafka configuration properties */
   val conf = new HashMap[String, Object]()
   conf.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
   conf.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
   conf.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")

   val producer = new KafkaProducer[String, String](conf)

   import org.apache.log4j.LogManager
   val LOG = Logger.getLogger("AVG-Producer")
   val producerId = "1"

   val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
   dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+1"))

   /* Send input data size to Graphite every minute */
   import java.net.Socket
   import java.io._
   import java.net._
   import scala.io._

   val prefix = prop.getProperty("producer.prefixMetrics") + ".customMetrics-avg"
   var producedDataSize = 0L
   var recordsDataSize = 0L
   var producerRows = 0L

/*
   val MINUTES = 1 // The delay in minutes
   val timer = new Timer()
   timer.schedule(new TimerTask() {
      override def run(): Unit =  { // Function runs every MINUTES minutes.
	
           val socket = new Socket(InetAddress.getByName("sky2.it.kth.se"), 2003)
           val out = new PrintStream(socket.getOutputStream)

	   val time = Calendar.getInstance().getTimeInMillis()/1000

           val mProducedDataSize = s"""${prefix}.producer.producedBytes ${producedDataSize} ${time}"""
           val mRecordsDataSize = s"""${prefix}.producer.recordsBytes ${recordsDataSize} ${time}"""
	   val mProducerRows = s"""${prefix}.producer.producedRows ${producerRows} ${time}"""

	   out.println(mProducedDataSize)
	   out.flush
	   out.println(mRecordsDataSize)
	   out.flush
	   out.println(mProducerRows)
	   out.flush

           LOG.warn(s"dataSize: ${producedDataSize}, recordsDataSize: ${recordsDataSize}, producedRows: ${producerRows}")

           socket.close

      }
   }, 0, 1000 * 60 * MINUTES)   // 1000 milliseconds in a second * 60 per minute * the MINUTES variable
*/

   // Get the word stream from the source 
   import scala.io.Source

   /* Compute milliseconds and nanoseconds for waiting anc calibrate producing rate */
   val rate = (1000.toDouble / rowsPerSecond.toDouble).toString.split("\\.")
   val millis = rate(0).toInt
   val nanos = String.format("%1$-" + 6 + "s", rate(1)).replace(' ', '0').toInt // pad with zeros to transform in nanoseconds

   while (true) {
	val timestampInMs = Calendar.getInstance().getTimeInMillis()
        val created_at = dateFormat.format(timestampInMs)
	
	val value = Random.nextInt(2000)

	val line  = s"""{"created_at":"${created_at}","number":${value},"producer_id":"${producerId}"}"""
	val msg = new ProducerRecord[String, String](topic, line)
	producer.send(msg)

	val msgBytes = msg.toString.getBytes.length
	val lineBytes = line.getBytes.length

	producedDataSize = producedDataSize + msgBytes
	recordsDataSize = recordsDataSize + lineBytes
	producerRows = producerRows + 1

//	LOG.warn(s"msgBytes: ${msgBytes}, lineBytes: ${line.getBytes.length}")		
//	LOG.warn(s"line: ${line}, msg: ${msg.toString}")	

	Thread.sleep(millis, nanos)
	
/*
	/* Store offset to avoid to  */
        val out = new FileOutputStream("producer.properties")
        producerProp.setProperty("producer.offset", offset.toString)
        producerProp.store(out, null)
        out.close()
*/
     }

  }
}
                         
