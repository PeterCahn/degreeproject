package com.pietro.thesis

// scalastyle:off println
import java.util.HashMap
import scala.io.Source
import scala.collection.immutable.StringOps
import scala.util.control.Exception
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}

import org.apache.spark.SparkConf
import org.apache.spark.streaming._
import org.apache.spark.streaming.kafka010._
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.kafka.clients.producer._

import org.apache.log4j._

/**
 * Consumes messages from one or more topics in Kafka and does wordcount.
 * Usage: KafkaWordCount <zkQuorum> <group> <topics> <numThreads>
 *   <zkQuorum> is a list of one or more zookeeper servers that make quorum
 *   <group> is the name of kafka consumer group
 *   <topics> is a list of one or more kafka topics to consume from
 *   <numThreads> is the number of threads the kafka consumer should use
 *
 * Example:
 *    `$ bin/run-example \
 *      org.apache.spark.examples.streaming.KafkaWordCount zoo01,zoo02,zoo03 \
 *      my-consumer-group topic1,topic2 1`
 */
object KafkaWordCount {
  def main(args: Array[String]) {
    if (args.length < 4) {
      System.err.println("Usage: KafkaWordCount <zkQuorum> <group> <topics> <numThreads>")
      System.exit(1)
    }

//    StreamingExamples.setStreamingLogLevels()

    val Array(zkQuorum, group, topics, numThreads) = args
    val sparkConf = new SparkConf().setAppName("KafkaWordCount")
    val ssc = new StreamingContext(sparkConf, Seconds(2))
    ssc.checkpoint("checkpoint")

    val topicMap = topics.split(",").map((_, numThreads.toInt)).toMap

/* START  */

val kafkaParams = Map[String, Object](
    "bootstrap.servers" -> "sky1.it.kth.se:9092",
    "key.deserializer" -> classOf[StringDeserializer],
    "value.deserializer" -> classOf[StringDeserializer],
    "group.id" -> "test",
    "auto.offset.reset" -> "earliest"
//    "enable.auto.commit" -> (false: java.lang.Boolean)
  )

  val lines = KafkaUtils.createDirectStream(ssc, PreferConsistent, Subscribe[String, String](Array("test"), kafkaParams))

/* END  */

//    val lines = KafkaUtils.createDirectStream(ssc, zkQuorum, group, topicMap).map(_._2)
    val words = lines.flatMap(_.value.split(" "))
    val wordCounts = words.map(x => (x, 1L))
      .reduceByKeyAndWindow(_ + _, _ - _, Minutes(10), Seconds(2), 2)
    wordCounts.print()

    ssc.start()
    ssc.awaitTermination()
  }
}

object KafkaWordCountProducer {

  def main(args: Array[String]) {
    if (args.length < 4) {
      System.err.println("Usage: KafkaWordCountProducer <metadataBrokerList> <topic> " +
        "<messagesPerSec> <wordsPerMessage>")
      System.exit(1)
    }

    val Array(brokers, topic, messagesPerSec, wordsPerMessage) = args
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
    val filename = "../../data/64060K0J4201701.dat"
  
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
	
      //val message = new ProducerRecord[String, String]("test", null, timestamp, timestamp.toString, line)
      val message = new ProducerRecord[String, String]("test", null, null, line)

      println("real ts: "+timestamp+" | "+message.timestamp())
      producer.send(message)


    }

    

  }
}
