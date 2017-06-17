package com.pietro.thesis

// scalastyle:off println
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
import scala.math.max

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}

import org.apache.spark.SparkConf
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream._
import org.apache.spark.streaming.kafka010._
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.clients.producer._
import org.apache.spark.rdd.RDD

import org.apache.log4j._
import scala.concurrent.duration._

object ComputeClusterLocal {
  def main(args: Array[String]) {    

    if (args.length < 3) {
      System.err.println("Usage: KafkaWordCount <brokers> <topic> <windowLength (in minutes)>")
      System.exit(1)
    }
    
    /* TO DO:
     * ParameteTopics and brokers parameter can be also taken from configuration files */
//    val inputKafkaTopic = "dataSky1"
    val outputKafkaTopic = "windowedSummaries"
//    val brokers = "sky1.it.kth.se:9092,sky2.it.kth.se:9092"
   
    val Array(brokers, inputKafkaTopic, winLength) = args
    val sparkConf = new SparkConf().setAppName("Thesis01")
    val ssc = new StreamingContext(sparkConf, Seconds(5))
    ssc.checkpoint("dataAggregation")

    val topics = inputKafkaTopic.split(",").toList
    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> brokers,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "testGroupSky1",
      "auto.offset.reset" -> "earliest",
      "enable.auto.commit" -> (true: java.lang.Boolean)
    )    
    
    /* Create stream from Kafka topics */
    val lines = KafkaUtils.createDirectStream(ssc, PreferConsistent, Subscribe[String, String](topics, kafkaParams))

    /* Extract only useful information from known line schema */
    val lineWithTemps = lines.map(_.value.split(" +"))
	.filter(line => line.length > 8) // prevent lines with less than 8 word: wrong or not supported format (in this version)
	.map(line => line(9).toLong )    // map each line to a temperature

    val windowLength = Minutes(winLength.toInt)
    val slideInterval = Seconds(5)

    lineWithTemps.window( windowLength, slideInterval )
       .foreachRDD( rdd => {
          if( !rdd.isEmpty ){

	    /* Compute sum and count on each RDD */
	    val count: Double = rdd.count().toDouble
            val sum: Long = rdd.reduce( _ + _ ).toLong
		
            /* Prepare to send message to Kafka topic  */
 	    val props = new HashMap[String, Object]()
	    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "sky1.it.kth.se:9092,sky2.it.kth.se:9092")
	    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
	    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
	    val producer = new KafkaProducer[String, String](props)
	    
   	    /* Prepare data format to put into the message */
	    val format = new SimpleDateFormat("yyyyMMddkkmmss")
	    val date = format.format(Calendar.getInstance().getTime())                
		  
 	    val data = date+", "+sum+", "+count+", "+sum/count+", "+windowLength+", 1" // record to publish
	    val message = new ProducerRecord[String, String](outputKafkaTopic, null, data)
	    
	    println(data) // debug purposes only

	    producer.send(message) // publish message into outputKafkaTopic
	    producer.close()
          }
       })

    ssc.start()
    ssc.awaitTermination()
  }
}

object Reconcile {
  def main(args: Array[String]) {
    if (args.length < 1) {
      System.err.println("Usage: KafkaWordCount <brokers>")
      System.exit(1)
    }    
    
    val inputKafkaTopic = "windowedSummaries"

    val Array(brokers) = args
    val sparkConf = new SparkConf().setAppName("Reconciliation")
    val ssc = new StreamingContext(sparkConf, Seconds(2))
    ssc.checkpoint("reconciliation")

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> brokers,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "mergingSummaryGroup",
      "auto.offset.reset" -> "earliest",
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )

    val records = KafkaUtils.createDirectStream(ssc, PreferConsistent, Subscribe[String, String](inputKafkaTopic.split(","), kafkaParams))

    val aggregationByTime = records.map( record => {
      val recordArr = record.value.trim.split(",")
      val key: Long = Try( recordArr(0).toLong ).toOption.getOrElse(-1)
      ( key, recordArr.drop(1).mkString(", ") )
    })
    .reduceByKey( (v1, v2) => {
	val vArr1 = v1.trim.split(", +")
	val vArr2 = v2.trim.split(", +")
	val sum = vArr1(0).toDouble + vArr2(0).toDouble
	val count = vArr1(1).toDouble + vArr2(1).toDouble
        " "+sum+", "+count+", "+sum/count+", "+vArr1(3)+", "+vArr1(4)+"|"+vArr2(4)
    })
    .transform( rdd => rdd.sortByKey(false) )
    //.take(10)

/*
    val onlyLastTimeAggregated = aggregationByTime.reduce( (x, y) => { 
        val maximumTs = max(x._1, y._1)
	if( maximumTs == x._1) x else y
    })
*/

    //onlyLastTimeAggregated.print()    
    aggregationByTime.print()

    ssc.start()
    ssc.awaitTermination()
  }
}


object DataProducer {

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
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")

    val producer = new KafkaProducer[String, String](props)

    /* ASOS Surface 1 Minute Data
     * https://www1.ncdc.noaa.gov/pub/data/documentlibrary/tddoc/td3285.pdf */
    val filename = "../data/64060K0J4201701.dat"
    
//    val format = new SimpleDateFormat("yyyyMMddkkmmss")
//    val dateTime = Calendar.getInstance().getTime()
    val swTs = (System.currentTimeMillis / 1000) + 7200 // adding 2 hours (3600 s * 2)
    var ts = swTs - 1200 // previous 20 minutes (5 min = 300 s => 20 min = 1200 s)

    for(line <- Source.fromFile(filename).getLines().filter( line => !Try( line.split(" +")(9).toLong ).toOption.isEmpty  )){
      val now = ((System.currentTimeMillis /1000) + 7200)
      if ( ts >= now ) Thread.sleep(60000)

      val words = line.split(" +")
      val eventTime = words(1).slice(3,15)      

      val message = new ProducerRecord[String, String](topic, null, ts, ts.toString, line)
      
      println( line.replaceAll("\\s+", " ") )
      producer.send(message)
      producer.close()
      ts = ts + 60

    }
  }

}
