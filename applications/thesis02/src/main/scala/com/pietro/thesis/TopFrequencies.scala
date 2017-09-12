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

object ComputeClusterLocal {
  def main(args: Array[String]) {    

/*    if (args.length < 3) {
      System.err.println("Usage: KafkaWordCount <brokers> <topic> <windowLength (in minutes)>")
      System.exit(1)
    }
*/  

    val prop = new Properties()
    prop.load(new FileInputStream("app.properties"))
   
    val sparkConf = new SparkConf().setAppName(prop.getProperty("cluster.appname"))
    val ssc = new StreamingContext(sparkConf, Seconds(5))
    ssc.checkpoint("dataAggregation")

    val topic = prop.getProperty("cluster.input.topic").split(",").toList
    val brokers = prop.getProperty("cluster.input.brokers")
    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> brokers,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "testGroupSky1",
      "auto.offset.reset" -> "earliest",
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )    
    
    /* Create stream from Kafka topics */
    val lines = KafkaUtils.createDirectStream(ssc, PreferConsistent, Subscribe[String, String](topic, kafkaParams))

    /* Extract only useful information from known line schema */
    val lineWithTemps = lines.map(_.value.split(" +"))
	.filter(line => line.length > 8) // prevent lines with less than 8 word: wrong or not supported format (in this version)
	.map(line => line(9).toLong )    // map each line to a temperature

    val clusterId = prop.getProperty("cluster.id")
    val windowLength = Minutes(prop.getProperty("cluster.windowlength.minutes").toInt)
    val slideInterval = Minutes(prop.getProperty("cluster.windowslide.minutes").toInt)

    val outputKafkaTopic = prop.getProperty("cluster.output.topic")
    val outputBrokers = prop.getProperty("cluster.output.brokers")

    lineWithTemps.window( windowLength, slideInterval )
       .foreachRDD( rdd => {
          if( !rdd.isEmpty ){

	    /* Compute sum and count on each RDD */
	    val count: Double = rdd.count().toDouble
            val sum: Long = rdd.reduce( _ + _ ).toLong
		
            /* Prepare to send message to Kafka topic  */
 	    val props = new HashMap[String, Object]()
	    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, outputBrokers)
	    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
	    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
	    val producer = new KafkaProducer[String, String](props)
	    
   	    /* Prepare data format to put into the message */
	    val format = new SimpleDateFormat("yyyyMMddkkmm")
	    val date = format.format(Calendar.getInstance().getTime())
		  
 	    val data = date+", "+sum+", "+count+", "+sum/count+", "+windowLength+", "+clusterId // record to publish
	    val message = new ProducerRecord[String, String](outputKafkaTopic, clusterId.toString, data)
	    
	    println(data) // debug purposes only

	    producer.send(message) // publish message into outputKafkaTopic
	    producer.close()
          }
       })

    ssc.start()
    ssc.awaitTermination()
  }
}

object PrepareQuery{
  def main(args: Array[String]) {
    if (args.length < 1) {
      System.err.println("Usage: KafkaWordCount <brokers>")
      System.exit(1)
    }

    val inputKafkaTopic = "windowedSummaries"
    val Array(brokers) = args

    val windowLength = Minutes(20)
    val sliding = Seconds(10)

    import org.apache.spark.sql._
    import org.apache.spark.sql.SparkSession
    import org.apache.spark.sql.Dataset
    import org.apache.spark.sql.Row
    import org.apache.spark.sql.streaming.StreamingQuery
    import org.apache.spark.sql.types._
    import org.apache.spark.sql.functions._

    val spark = SparkSession
        .builder()
        .appName("PreparingQuery")
        .getOrCreate()

    import spark.implicits._

    val schema = new StructType()
        .add("time", LongType)
        .add("sum", DoubleType)
        .add("count", DoubleType)
        .add("average", DoubleType)
        .add("windowLength", StringType)
        .add("clusterId", IntegerType)
//import org.json4s._
//import org.json4s.native.Serialization._
//import org.json4s.native.Serialization
//implicit val formats = Serialization.formats(NoTypeHints)
//import org.json4s.native.Json
//import org.json4s.DefaultFormats

    val records = spark
        .readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", brokers)
        .option("subscribe", inputKafkaTopic)
        .option("startingOffsets", "earliest")
        .load()
	.selectExpr("cast (value as string)")
	.as[String]
	.map( record => {
/*		val recordArr = record.trim.split(", +")
		val m = Map(
			"time" -> recordArr(0).toLong,
			"sum" -> recordArr(1).toDouble,
			"count" -> recordArr(2).toDouble,
			"average" -> recordArr(3).toDouble,
			"windowLength" -> recordArr(4),
			"clusterId" -> recordArr(5).toInt
		)
		Json(DefaultFormats).write(m)
*/


	        val recordArr = record.trim.split(", +")
		s"""{time:${recordArr(0).toLong},sum:${recordArr(1).toDouble},count:${recordArr(2).toDouble},average:${recordArr(3).toDouble},windowLength:${recordArr(4)},clusterId:${recordArr(5).toInt}}""".stripMargin
	    })

    records.printSchema()

    val parsed1 =  records
	.select(from_json('value, schema) as 'parsed)
    parsed1.printSchema()
    
    val parsed2 = parsed1
	.select("parsed.*")
    parsed2.printSchema()

//    parsed.printSchema()

    val output = parsed1
    .writeStream
    .format("console")
    .outputMode("append")
    .start

    output.awaitTermination
/*
  
//    .createOrReplaceTempView("WSTable")

//    val sql = "SELECT * FROM WSTable"
//    val ageAverage = spark.sql(sql)

    val query: StreamingQuery = data.writeStream
      .outputMode("append")
      .format("console")
      .start()

    query.awaitTermination()
*/
  }
}


object MultipleDataProducer {

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

   /* Prepare data to be parallelized */
   val data = Seq.range(1, nproducers.toInt)
   val producers = sc.parallelize(data)

   /* Send wrapper containing Kafka Producer to produce in parallel from different executors */
   val ks = sc.broadcast(KafkaSink(conf))

   /*  */
   val dateFormat = new SimpleDateFormat("yyyyMMddkkmm")
   dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+1"))
   val now = Calendar.getInstance().getTime()
   val time = dateFormat.format(now)

   producers.foreach( rdd => {
	
	val data = Seq.fill(dataSize.toInt)(Random.nextInt(200))

        val currentTimeInMs = Calendar.getInstance().getTimeInMillis()
        val currentTimeInMinutes = currentTimeInMs/1000/60

	var timestampInMinutes = currentTimeInMinutes - 120

	for(value <- 1 to dataSize.toInt){

		if ( timestampInMinutes >= currentTimeInMinutes ) Thread.sleep(60000)

		val timestampInMs = Calendar.getInstance().getTimeInMillis()
//	        val formattedCurrentTime = dateFormat.format(timestampInMs)
		val formattedCurrentTime = Calendar.getInstance().getTime()
		

		val toSend = s"""{"time_ms":${timestampInMs},"when":${formattedCurrentTime},"number":${value},"producer_id":${rdd}}""".stripMargin
	        ks.value.send(topic, timestampInMs, Random.nextInt(2000).toString, toSend)
		timestampInMinutes = timestampInMinutes + 1

    	}

   })

 }
}
