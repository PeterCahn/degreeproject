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

object ClusterLocalAVGAggregate {
  def main(args: Array[String]) {    

/*    if (args.length < 3) {
      System.err.println("Usage: KafkaWordCount <brokers> <topic> <windowLength (in minutes)>")
      System.exit(1)
    }
*/  

    val prop = new Properties()
    prop.load(new FileInputStream("app.properties"))
    val clusterId = prop.getProperty("cluster.id")
    
    /* Input Kafka configuration (which topic read as input and from which server) */
    val inputTopic = prop.getProperty("cluster.input.topic")
    val inputBrokers = prop.getProperty("cluster.input.brokers")

    /* Output Kafka configuration (which topic to write on and from which server) */
    val outputTopic = prop.getProperty("cluster.output.topic")
    val outputBrokers = prop.getProperty("cluster.output.brokers")

    /* Sliding window parameters */
    val windowLength = Minutes(prop.getProperty("cluster.windowlength.minutes").toInt)
    val slideInterval = Minutes(prop.getProperty("cluster.windowslide.minutes").toInt)
    
    import org.apache.spark.sql._
    import org.apache.spark.sql.SparkSession
    import org.apache.spark.sql.Dataset
    import org.apache.spark.sql.Row
    import org.apache.spark.sql.streaming.StreamingQuery
    import org.apache.spark.sql.types._
    import org.apache.spark.sql.functions._
//    import org.apache.spark.sql.functions.typedLit // Spark 2.2+
    import org.apache.spark.sql.functions.lit

    val spark = SparkSession
        .builder()
        .appName("SSComputeClusterLocal")
        .getOrCreate()

    import spark.implicits._

    val schema = new StructType()
        .add("when", StringType)
        .add("number", IntegerType)
        .add("producer_id", IntegerType)

    val records = spark
        .readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", inputBrokers)
        .option("subscribe", inputTopic)
        .option("startingOffsets", "earliest")
        .load()
	.selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)") // cast key and value as string to be read in the following

   /* Extract 'value' according to provided schema and adding column with timestamp */
   val parsedRecords = records
	.select(from_json($"value", schema) as "parsed")
        .select( unix_timestamp($"parsed.when", "yyyy-MM-dd'T'HH:mm:ss").cast("timestamp").alias("timestamp"), $"parsed.*")
    
    /* Windowed computation: count all elements and sum them up */
    val windowedRecords = parsedRecords
	.groupBy(window($"timestamp", "10 minutes", "1 minute") as "window")
        .agg(count("*") as "count", sum("number") as "sum", avg("number") as "average") 
	.filter($"window.end".as("timestamp") < current_timestamp()) // filter window starting now and ending 'windowLength' in the future
	.orderBy($"window".desc)
	.withColumn("clusterId", lit(clusterId))        
//	.selectExpr("CAST(window AS STRING) AS key", "to_json(struct(*)) AS value")

    /* Show schema after computation */
    windowedRecords.printSchema()
    
    /* Print on console */
    val toConsole = windowedRecords
	.writeStream
   	.format("console")
	.option("truncate","false")
	.outputMode("complete")
    	.start()

    import org.apache.spark.sql.streaming.OutputMode
    import org.apache.spark.sql.streaming.Trigger

    /* Produce on Kafka topic */
    val toKafka = windowedRecords
	.selectExpr("CAST(window AS STRING) AS key", "to_json(struct(*)) AS value")
        .writeStream
	.outputMode("complete")
//	.trigger(Trigger.ProcessingTime("1 second"))
        .format("kafka")
        .option("kafka.bootstrap.servers", outputBrokers)
        .option("topic", outputTopic)
        .option("checkpointLocation", "ccl-ss") // ComputeClusterLocal-StructuredStreaming
        .start()

    toConsole.awaitTermination
    toKafka.awaitTermination

  }
}

object DecentralizedAVGAggregate{
  def main(args: Array[String]) {

    val prop = new Properties()
    prop.load(new FileInputStream("app.properties"))
    val inputKafkaTopic = prop.getProperty("dec.input.topic")
    val inputKafkaBroker = prop.getProperty("dec.input.brokers")

    val windowLength = Minutes(60)
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
        .appName("DecentralizedAVGAggregate")
        .getOrCreate()

    import spark.implicits._

    val schema = new StructType()
	.add("window", new StructType().add("start", TimestampType).add("end", TimestampType))
	.add("count", LongType)
	.add("sum", LongType)
	.add("average", DoubleType)
	.add("clusterId",StringType)

    val records = spark
        .readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", inputKafkaBroker)
        .option("subscribe", inputKafkaTopic)
        .option("startingOffsets", "earliest")
        .load()
        .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)") // cast key and value as string to be read in the following

    /* Extract 'value' according to provided schema and adding column with timestamp */
    val parsedRecords = records
	.select(from_json($"value", schema) as "parsed")
        .select($"parsed.*")

    /* Windowed computation: count all elements and sum them up */
    val windowedRecords = parsedRecords
	.withColumn("windowEnd", $"window.end")
	.withColumn("windoowStart", $"window.start")
        .groupBy(window($"windowEnd", "60 minutes", "1 minute") as "window")
//	.filter() // Filter records whose "window.end" falls outside current window computation
	.agg(sum("sum") as "aggregateSum", count("count") as "aggregateCount", avg("sum") as "aggregateAvg")
        .filter($"window.end".as("timestamp") < current_timestamp()) // filter window starting now and ending 'windowLength' in the future
        .orderBy($"window".desc)
	
//        .selectExpr("CAST(window AS STRING) AS key", "to_json(struct(*)) AS value") // Prepare to write back to Kafka

    windowedRecords.printSchema()

    /* Print on console */
    val toConsole = windowedRecords
        .writeStream
        .format("console")
        .option("truncate","false")
        .outputMode("complete")
        .start()

    toConsole.awaitTermination
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


object MultiDataProducerAVGAggregate {

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

   /* Date format: yyyy-MM-dd'T'hh:mm:ss */
   val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
   dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+2"))
   val now = Calendar.getInstance().getTime()
   val time = dateFormat.format(now)

   producers.foreach( rdd => {
	
	val data = Seq.fill(dataSize.toInt)(Random.nextInt(200))

        val currentTimeInMs = Calendar.getInstance().getTimeInMillis()
        val currentTimeInMinutes = currentTimeInMs/1000/60

	var timestampInMinutes = currentTimeInMinutes - 120

	for(value <- 1 to dataSize.toInt){

		if ( timestampInMinutes >= currentTimeInMinutes ) Thread.sleep(10000)

		val timestampInMs = Calendar.getInstance().getTimeInMillis()
	        val formattedCurrentTime = dateFormat.format(timestampInMs)
//		val formattedCurrentTime = Calendar.getInstance().getTime()
		

		val toSend = s"""{"when":"${formattedCurrentTime}","number":${value},"producer_id":${rdd}}""".stripMargin
	        ks.value.send(topic, timestampInMs, Random.nextInt(2000).toString, toSend)
		timestampInMinutes = timestampInMinutes + 1

    	}

   })

 }
}
