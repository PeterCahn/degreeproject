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

    val outputKafkaTopic = "windowedSummaries"

    val Array(brokers, topic) = args
    val sparkConf = new SparkConf().setAppName("Thesis01")
    val ssc = new StreamingContext(sparkConf, Seconds(2))
    ssc.checkpoint("checkpoint")

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> brokers,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "testGroupSky1",
      "auto.offset.reset" -> "earliest",
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )    

    val lines = KafkaUtils.createDirectStream(ssc, PreferConsistent, Subscribe[String, String](topic.split(","), kafkaParams))

    val lineWithTemps = lines.map(_.value.split(" +"))
	.filter(line => line.length > 8)
	.map(line => line(9).toLong )

    val windowLength = Minutes(1)
    val slideInterval = Seconds(2)

    lineWithTemps.window( windowLength, slideInterval )
       .foreachRDD( rdd => {
          if( !rdd.isEmpty){

	    /* Compute sum and count */
	    val count: Double = rdd.count().toDouble
            val sum: Long = rdd.reduce( _ + _ ).toLong
		
            /* Prepare to send message to Kafka topic  */
 	    val props = new HashMap[String, Object]()
	    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
	    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
	    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
	    val producer = new KafkaProducer[String, String](props)
	    
   	    /* Prepare data format to put into the message */
	    val format = new SimpleDateFormat("yyyyMMddkkmm")
	    val date = format.format(Calendar.getInstance().getTime())                
		  
 	    val data = date+", "+sum+", "+count+", "+sum/count+", "+windowLength+", 1"
	    val message = new ProducerRecord[String, String](outputKafkaTopic, null, data)	  
	    
	    println(data)   	    
	
	    producer.send(message)
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
      ( recordArr(0).toLong, recordArr.drop(1).mkString(", ") )
    })
    .reduceByKey( (v1, v2) => {
	val vArr1 = v1.trim.split(", +")
	val vArr2 = v2.trim.split(", +")
	val sum = vArr1(0).toDouble + vArr2(0).toDouble
	val count = vArr1(1).toDouble + vArr2(1).toDouble
	println(sum+", "+count+", "+sum/count)
        sum+", "+count+", "+sum/count+", "+vArr1(3)
    })

    val onlyLastTimeAggregated = aggregationByTime.reduce( (x, y) => { 
        val maximumTs = max(x._1, y._1)
	if( maximumTs == x._1) x else y
    })

    onlyLastTimeAggregated.print()    

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
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")

    val producer = new KafkaProducer[String, String](props)

    /* ASOS Surface 1 Minute Data
     * https://www1.ncdc.noaa.gov/pub/data/documentlibrary/tddoc/td3285.pdf */
    val filename = "../data/64060K0J4201701.dat"
  
//    for(line <- Source.fromFile(filename).getLines().filter( line => !safeStringToLong(line.split(" +")(9)).isEmpty  )){
    for(line <- Source.fromFile(filename).getLines().filter( line => !Try( line.split(" +")(9).toLong ).toOption.isEmpty  )){
      val words = line.split(" +")
      //if( Some(words(9).toLong).getOrElse("NO") == "NO" ) break
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
      Thread.sleep(scala.util.Random.nextInt(2000))
      producer.send(message)

    }
  }

      def safeStringToLong(str: String): Option[Long] = {
        catching(classOf[NumberFormatException]) opt str.toLong
}


}
