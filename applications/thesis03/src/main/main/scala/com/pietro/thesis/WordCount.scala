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

import org.apache.log4j._
import scala.concurrent.duration._

object WCProducer {

  def main(args: Array[String]) {
    if (args.length < 2) {
      System.err.println("Usage: KafkaWordCountProducer <number of producers> <data_size>")
      System.exit(1)
    }

   class KafkaSink(createProducer: () => KafkaProducer[String, String]) extends Serializable {
     lazy val producer = createProducer()
     def send(topic: String, ts: Long, producerId: String, toSend: String): Unit = producer.send(new ProducerRecord[String, String](topic, null, ts, producerId, toSend))
   }

   object KafkaSink {
     def apply(config: HashMap[String, Object]): KafkaSink = {
       val f = () => {
         val producer = new KafkaProducer[String, String](config)

         sys.addShutdownHook {
           producer.close()
         }
         producer
       }
       new KafkaSink(f)
     }
   }

   
   val Array(nproducers, dataSize) = args
   BasicConfigurator.configure()

   /* Create SparkContext */
   val sc = new SparkContext(new SparkConf().setAppName("KafkaMultiProducer"))

   /* Extract configuration from property file */
   val prop = new Properties()
   prop.load(new FileInputStream("app.properties"))
   val brokers = prop.getProperty("producer.brokers")
   val topic = prop.getProperty("producer.topic")

   /* Set kafka configuration properties */
   val conf = new HashMap[String, Object]()
   conf.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
   conf.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
   conf.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")

   // Get the word stream from the source 
   val textFile = sc.textFile("src/main/scala/com/pietro/thesis/file")
   val counts = textFile.flatMap(line => line.replaceAll("^[.,\\s]+", "").split("[.,\\s]+") ) //line.split("[^\\p{L}0-9']+|\\s+"))
                .map(word => (word, 1))

   /* Send wrapper containing Kafka Producer to produce in parallel from different executors */
   val ks = sc.broadcast(KafkaSink(conf))

   /* Date format: yyyy-MM-dd'T'hh:mm:ss */
   val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
   dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+2"))

   val currentTimeInMs = Calendar.getInstance().getTimeInMillis()
   val currentTimeInMinutes = currentTimeInMs/1000/60

   var timestampInMinutes = currentTimeInMinutes - 120

   counts.foreach( t => {
           val timestampInMs = Calendar.getInstance().getTimeInMillis()
           val formattedCurrentTime = dateFormat.format(timestampInMs)
//         val formattedCurrentTime = Calendar.getInstance().getTime()

           val toSend = s"""{"when":"${formattedCurrentTime}","number":"${t._1}","producer_id":1}""".stripMargin
           ks.value.send(topic, timestampInMs, Random.nextInt(2000).toString, toSend)
           timestampInMinutes = timestampInMinutes + 1

        }) 

  }
}
                         
