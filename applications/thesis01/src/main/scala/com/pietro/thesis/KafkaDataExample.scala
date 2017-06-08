package com.pietro.thesis

// scalastyle:off println
import java.util.HashMap
import scala.io.Source
import scala.collection.immutable.StringOps
import scala.util.control.Exception
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.TimeZone
import scala.util.Random

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}

import org.apache.spark.SparkConf
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream._
import org.apache.spark.streaming.kafka010._
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.kafka.clients.producer._
import org.apache.spark.rdd.RDD

import org.apache.log4j._
import scala.concurrent.duration._

object KafkaWordCount {
  def main(args: Array[String]) {
    if (args.length < 2) {
      System.err.println("Usage: KafkaWordCount <brokers> <topic>")
      System.exit(1)
    }

    val Array(brokers, topic) = args
    val sparkConf = new SparkConf().setAppName("Thesis01")
    val ssc = new StreamingContext(sparkConf, Seconds(2))
    ssc.checkpoint("checkpoint")

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> brokers,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "testGroup",
      "auto.offset.reset" -> "earliest",
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )    

    val lines = KafkaUtils.createDirectStream(ssc, PreferConsistent, Subscribe[String, String](topic.split(","), kafkaParams))

/** WORKING **
    val words = lines.flatMap(_.value.split(" +"))
    

    val wordCounts = words.map(x => (x, 1L))
      .reduceByKeyAndWindow(_ + _, _ - _, Minutes(10), Seconds(2), 2)

    val sortedCounts = wordCounts.map { case(word, count) => (count, word) }
      .transform(rdd =>  rdd.sortByKey(false) )

    sortedCounts.foreachRDD(rdd =>
      println("\nTop 10 words:\n" + rdd.take(10).mkString("\n")) )
***	***/

    val lineWithTemps = lines.map(_.value.split(" +")).map(line => line(9).toLong )

    val windowLength = Minutes(1)
    val slideInterval = Seconds(2)

    val sum = lineWithTemps.window( windowLength, slideInterval )
       .foreachRDD( rdd => {
          if(!rdd.isEmpty){
	    val count = rdd.count().toDouble
            val sum = rdd.reduce( _ + _ ).toLong
            println("("+sum+", "+count+", "+sum/count+")")
         }
       })

    ssc.start()
    ssc.awaitTermination()
  }
}

object KafkaWordCountProducer {

  def main(args: Array[String]) {
    if (args.length < 2) {
      System.err.println("Usage: KafkaWordCountProducer <metadataBrokerList> <topic> ")
      System.exit(1)
    }

    val Array(brokers, topic) = args
    BasicConfigurator.configure()

    // Zookeeper connection properties
    val props = new HashMap[String, Object]()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
      "org.apache.kafka.common.serialization.StringSerializer")
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
      "org.apache.kafka.common.serialization.StringSerializer")

    val producer = new KafkaProducer[String, String](props)

    /* ASOS Surface 1 Minute Data
     * https://www1.ncdc.noaa.gov/pub/data/documentlibrary/tddoc/td3285.pdf */
    val filename = "../data/64060K0J4201701.dat"
  
    for(line <- Source.fromFile(filename).getLines()) {
      val words = line.split(" +")
      val date = words(1).slice(3,11)
      val hour = words(1).slice(11,13)
      val min  = words(1).slice(13,15)
      //println(date+","+hour+":"+min)

      /* build timestamp from date */
      val dfm = new SimpleDateFormat("yyyyMMddHHmm")
      dfm.setTimeZone(TimeZone.getTimeZone("GMT+1:00"))
      val ts = dfm.parse(date+""+hour+""+min).getTime()
      val timestamp = ts/1000
      //println(timestamp)
      
      val message = new ProducerRecord[String, String](topic, null, timestamp, timestamp.toString, line)
      
      println( "ts: "+ts+" | "+ line.replaceAll("\\s+", " ") )
      Thread.sleep(scala.util.Random.nextInt(10000))
      producer.send(message)

    }
  }
}
