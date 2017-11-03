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

object ClusterLocalMGSummary {
  def main(args: Array[String]) {    

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
    
//    spark.conf
//	.set("spark.sql.streaming.metricsEnabled", "true")
//	.set("spark.metrics.conf.*.sink.graphite.class", "org.apache.spark.metrics.sink.GraphiteSink")
//	.set("spark.metrics.conf.*.sink.graphite.host", "sky1.it.kth.se")
//        .set("spark.metrics.conf.*.sink.graphite.port", "2003")

    import spark.implicits._

    val schema = new StructType()
        .add("created_at", StringType)
        .add("value", StringType)
        .add("producer_id", StringType)

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
        .select( unix_timestamp($"parsed.created_at", "yyyy-MM-dd'T'HH:mm:ss").cast("timestamp").alias("timestamp"), $"parsed.*")

    spark.udf.register("myAverage", CreateMGSummary)

    import org.apache.spark.sql.expressions.UserDefinedAggregateFunction
    lazy val summary: UserDefinedAggregateFunction = CreateMGSummary

    /* Windowed computation: count all elements and sum them up */
    val windowedRecords = parsedRecords
	.groupBy(window($"timestamp", "10 minutes", "1 minute") as "window")
	.agg(summary('value).as("MG summary"))
	.filter($"window.end".as("timestamp") < current_timestamp()) // filter window starting now and ending 'windowLength' in the future
	.orderBy($"window".desc)
	.withColumn("clusterId", lit(clusterId)) 

    /* Show schema after computation */
    windowedRecords.printSchema()
    
    import org.apache.spark.sql.streaming.{OutputMode, Trigger, ProcessingTime}

    /* Print on console */
    val toConsole = windowedRecords
	.writeStream
   	.format("console")
	.option("truncate","false")
	.outputMode("complete")
	.trigger(ProcessingTime(10.seconds))
    	.start()

    /* Produce on Kafka topic */
    val toKafka = windowedRecords
	.selectExpr("CAST(window AS STRING) AS key", "to_json(struct(*)) AS value")
        .writeStream
	.outputMode("complete")
	.trigger(ProcessingTime(20.seconds))
        .format("kafka")
        .option("kafka.bootstrap.servers", outputBrokers)
        .option("topic", outputTopic)
        .option("checkpointLocation", "ccl-ss") // ComputeClusterLocal-StructuredStreaming
        .start()
/*
    /* Add listener to extract statistics */
    import org.apache.spark.sql.streaming.StreamingQueryListener
    import org.apache.spark.sql.streaming.StreamingQueryListener.{QueryProgressEvent, QueryStartedEvent, QueryTerminatedEvent}

    val queryListener: StreamingQueryListener = new StreamingQueryListener() {

	/* When posted: Right after StreamExecution has started running streaming batches */
        override def onQueryStarted(queryStarted: QueryStartedEvent): Unit = {
            println("Query started: " + queryStarted.id)
        }

	/* When posted: Right before StreamExecution finishes running streaming batches (due to a stop or an exception) */
        override def onQueryTerminated(queryTerminated: QueryTerminatedEvent): Unit = {
            println("Query terminated: " + queryTerminated.id)
        }

        /* When posted: ProgressReporter reports query progress (which is when StreamExecution runs batches and a trigger has finished) */
        override def onQueryProgress(queryProgress: QueryProgressEvent): Unit = {
            println("Query made progress: " + queryProgress.progress.numInputRows )
        }
    }
    spark.streams.addListener(queryListener)

//    spark.streams.removeListener(queryListener)
*/
    toConsole.awaitTermination
    toKafka.awaitTermination
  }


  import org.apache.spark.sql.{Row, SparkSession}
  import org.apache.spark.sql.expressions.MutableAggregationBuffer
  import org.apache.spark.sql.expressions.UserDefinedAggregateFunction
  import org.apache.spark.sql.types._
  import org.apache.spark.annotation.InterfaceStability
  import org.apache.spark.sql.{Column, Row}
  import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, Complete}
  import org.apache.spark.sql.execution.aggregate.ScalaUDAF

  object CreateMGSummary extends UserDefinedAggregateFunction {

     val n = 100

     // Data types of input arguments of this aggregate function
     def inputSchema: StructType = new StructType().add("element", StringType)

     // Data types of values in the aggregation buffer
     def bufferSchema: StructType = new StructType().add("buffer1", MapType(StringType, LongType))
   
     // The data type of the returned value
     def dataType: DataType = StringType
   
     // Whether this function always returns the same output on the identical input
     def deterministic: Boolean = true

     // Initializes the given aggregation buffer. The buffer itself is a `Row` that in addition to
     // standard methods like retrieving a value at an index (e.g., get(), getBoolean()), provides
     // the opportunity to update its values. Note that arrays and maps inside the buffer are still
     // immutable.
     def initialize(buffer: MutableAggregationBuffer): Unit = {
       buffer(0) = Map()
     }
   
     // Updates the given aggregation buffer `buffer` with new input data from `input`
     def update(buffer: MutableAggregationBuffer, input: Row): Unit = {
       if (!input.isNullAt(0)) {
   	   var map = buffer.getAs[Map[String, Long]](0)
	   val keyToAdd = input.getString(0)
   	   
	   if(map.contains(keyToAdd)){
              /* The elements is already contained: increment the count */
	      val value = map.get(keyToAdd).get + 1
	      map = map + (keyToAdd -> value)
	   } else if(map.size < n)
              /* There is space to add the element in the summary */
	      map = map + (keyToAdd -> 1)
	   else {
	      /* There is no space to add the element: decrement all counters and delete null ones */
	      var m = map
	      for( (k: String, v: Long) <- map)
		m = m + (k -> (v-1).toLong)
    	      
	      map = m.filter( (element) => element._2 > 0 )
	   }

	   buffer(0) = map
       }
     }
      
     // Merges two aggregation buffers and stores the updated buffer values back to `buffer1`
     def merge(buffer1: MutableAggregationBuffer, buffer2: Row): Unit = {

	var list = List[(String, Long)]()
	if (!buffer2.isNullAt(0) && !buffer1.isNullAt(0)){
   	   list = buffer1.getAs[Map[String, Long]](0).toList ::: buffer2.getAs[Map[String, Long]](0).toList
	}	

	if(list.length > 0){
	   def sum(xs: List[Long]): Long = {
  	     xs match {
	       case x :: tail => x + sum(tail) // if there is an element, add it to the sum of the tail
	       case Nil => 0 // if there are no elements, then the sum is 0
	     }
	   }

	   var sortedList = list.groupBy(_._1)
	      .map { case (k,v) => (k, sum(v.map(_._2))) }
	      .toList
	      .sortWith(_._2 > _._2)
/*
 	   val reducedList = sortedList.take(n+1)
	   var minValue = 0L
	   if(reducedList.length >= (n+1))
	      minValue = reducedList.minBy(_._2)._2

	   var map = reducedList.toMap
 	   for( (k: String, v: Long) <- map)
	       map = map + (k -> (v-minValue).toLong)
         
	   buffer1(0) = map.filter( (element) => element._2 > 0 ).toMap
*/
	   buffer1(0) = sortedList.take(n).toMap

	} 
     }

     // Calculates the final result
     def evaluate(buffer: Row): String = {
	var i = 0
	var summary = ""
	val map = buffer.getAs[Map[String, Long]](0).toSeq.sortWith(_._2 > _._2)
	for( (k,v) <- map) {
	   if( i == 0 ) {
	      summary = summary + s"""{"value":"${k}","frequency":${v}}"""
	      i = 1
	   } else
              summary = summary + s""",{"value":"${k}","frequency":${v}}"""	   

	}
	   
	s"""{"size":${map.size},"summary":[${summary}]}"""
	
     }
   }
}

object DecentralizedMGMerge {
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
        .appName("DecentralizedMGMerge")
        .getOrCreate()

    import spark.implicits._

    val summarySchema = new StructType()
        .add("size", LongType)
        .add("summary", ArrayType(new StructType().add("value", StringType).add("frequency", LongType)))

    val schema = new StructType()
	.add("window", new StructType().add("start", TimestampType).add("end", TimestampType))
	.add("MG summary", StringType)
	.add("clusterId", StringType)

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
        .select(from_json($"MG summary", summarySchema) as "parsed", $"window", $"clusterId")
        .select($"window", $"parsed.*", $"clusterId")

    spark.udf.register("decentralizedSummary", CreateDecentralizedMGSummary)

    import org.apache.spark.sql.expressions.UserDefinedAggregateFunction
    lazy val decentralizedSummary: UserDefinedAggregateFunction = CreateDecentralizedMGSummary

    /* Windowed computation: count all elements and sum them up */
    val windowedRecords = parsedRecords
	.withColumnRenamed("window", "inputWindow") // window field of each record is taken as input from udaf
	.withColumn("windowEnd", $"inputWindow.end")
	.groupBy( window($"windowEnd", "60 minutes", "1 minute") as "bigWindow" )
	.agg( decentralizedSummary($"windowEnd", $"summary", $"clusterId", $"window", $"inputWindow") as "mergedSummary")
	.select($"mergedSummary.*")
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

  }

   import org.apache.spark.sql.{Row, SparkSession}
   import org.apache.spark.sql.expressions.MutableAggregationBuffer
   import org.apache.spark.sql.expressions.UserDefinedAggregateFunction
   import org.apache.spark.sql.types._
   import org.apache.spark.annotation.InterfaceStability
   import org.apache.spark.sql.{Column, Row}
   import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, Complete}
   import org.apache.spark.sql.execution.aggregate.ScalaUDAF
   import scala.collection.mutable.WrappedArray
   import java.sql.Timestamp

   object CreateDecentralizedMGSummary extends UserDefinedAggregateFunction {

     val n = 30

     // Data types of input arguments of this aggregate function
     def inputSchema: StructType = new StructType()
	.add("currentTime", TimestampType)
        .add("summary", ArrayType(new StructType().add("value", StringType).add("frequency", LongType)))
	.add("clusterId", StringType)
        .add("currentBigWindow", new StructType().add("start", TimestampType).add("end", TimestampType))
	.add("inputWindow", new StructType().add("start", TimestampType).add("end", TimestampType))

     // Data types of values in the aggregation buffer
     def bufferSchema: StructType = new StructType()
	.add("partialSummary", ArrayType(new StructType().add("value", StringType).add("frequency", LongType)))
	.add("clusters", ArrayType(StringType))
        .add("window", new StructType().add("start", TimestampType).add("end", TimestampType))

     // The data type of the returned value
     def dataType = new StructType()
//        .add("window", new StructType().add("start", TimestampType).add("end", TimestampType))
        .add("globalSummary", ArrayType(new StructType().add("value", StringType).add("frequency", LongType)))
        .add("clusters", ArrayType(StringType))
        .add("window", new StructType().add("start", TimestampType).add("end", TimestampType))

     // Whether this function always returns the same output on the identical input
     def deterministic: Boolean = true

     // Initializes the given aggregation buffer. The buffer itself is a `Row` that in addition to
     // standard methods like retrieving a value at an index (e.g., get(), getBoolean()), provides
     // the opportunity to update its values. Note that arrays and maps inside the buffer are still
     // immutable.
     def initialize(buffer: MutableAggregationBuffer): Unit = {
	buffer(0) = Array()
	buffer(1) = Array()
	buffer(2) = (new Timestamp(0), new Timestamp(0))
     }

     def update(buffer: MutableAggregationBuffer, input: Row): Unit = {	
	/* Check window times: set start time and end time only once at the beginning */
	if( !buffer.isNullAt(2) ) {
	   /* Take window currently stored into buffer */
	   val bufferWindowRow = buffer.getAs[Row](2)
           var bufferWindow = (bufferWindowRow.getAs[Timestamp](0), bufferWindowRow.getAs[Timestamp](1))
	   
	   /* Check if bufferWindow has not been set, otherwise set to the window taken from input */
	   if( bufferWindow._1.equals(new Timestamp(0)) && bufferWindow._2.equals(new Timestamp(0)) ){
	      val windowRow = input.getAs[Row](3)
	      bufferWindow = (windowRow.getAs[Timestamp](0), windowRow.getAs[Timestamp](1))
	      buffer(2) = (bufferWindow._1, bufferWindow._2)

	   }
	}
	   
	   /* Window in the buffer must be set: update summary for each input windowed summary */ 
	   if(!input.isNullAt(4)){
	      val currentWindowRow = input.getAs[Row](4)
              val currentWindow = (currentWindowRow.getAs[Timestamp](0), currentWindowRow.getAs[Timestamp](1))

//	      if(currentWindow._1.after(bufferWindow._1) && currentWindow._2.before(bufferWindow._2) ){

		 /* Update summary merging them */
	         val globalSummary = buffer.getAs[Seq[Row]](0).map{case Row(k: String, v: Long) => (k, v)}
        	 val inputSummary = input.getAs[Seq[Row]](1).map{case Row(k: String, v: Long) => (k, v)}
		 
		 /* Append second summary to the first one */
	         val unionSummary = globalSummary ++ inputSummary

		 /* Sum counters of same key */
	         var updatedSummary = unionSummary.toList.groupBy(_._1)
	           .map { case (k, v) => (k, sum(v.map(_._2))) }
	           .toList
	           .sortWith(_._2 > _._2)

		 /* Get final summary by applying MG merging algorithm */
	         val reducedList = updatedSummary.take(n+1) // take first n+1 elements: (n+1)-th is the minimum one
        	 var minValue = 0L
	         if(reducedList.length >= (n+1))
        	    minValue = reducedList.minBy(_._2)._2 // take minimum of counters: value of counter of last element is (n+1)-th

	         var map = reducedList.toMap
        	 for( (k: String, v: Long) <- map)
	            map = map + (k -> (v-minValue).toLong) // Subtract (n+1)-th element from all counters

	         buffer(0) = map.filter( (element) => element._2 > 0 ).toArray // Keep positive counters only

//	         buffer(0) = finalSummary

        	 /* Update clusters involved */
	         if(!input.isNullAt(2))
	           buffer(1) = (buffer.getAs[Seq[String]](1).toArray ++ Array(input.getString(2)))	        

//	      }
	   }	
       
     }

     def sum(xs: List[Long]): Long = {
	xs match {
           case x :: tail => x + sum(tail) // if there is an element, add it to the sum of the tail
           case Nil => 0 // if there are no elements, then the sum is 0
        }
     }

     def merge(buffer1: MutableAggregationBuffer, buffer2: Row): Unit = {
	/* Update clusters involved */
	val clusters = (buffer1.getAs[Seq[String]](1) ++ buffer2.getAs[Seq[String]](1)).distinct
	buffer1(1) = clusters

	/* Merge two summaries */
        val sum1 = buffer1.getAs[Seq[Row]](0).map{case Row(k: String, v: Long) => (k, v)}
        val sum2 = buffer2.getAs[Seq[Row]](0).map{case Row(k: String, v: Long) => (k, v)}
        val newSum = sum1 ++ sum2

        var finalSummary = newSum.toList.groupBy(_._1)
           .map { case (k, v) => (k, sum(v.map(_._2))) }
           .toList
           .sortWith(_._2 > _._2)

        buffer1(0) = finalSummary.take(n)

	/* Check window boundaries */
	if( !buffer1.isNullAt(2) && !buffer2.isNullAt(2) ){
          val bufferWindowRow1 = buffer1.getAs[Row](2)
          var bufferWindow1 = (bufferWindowRow1.getAs[Timestamp](0), bufferWindowRow1.getAs[Timestamp](1))
          val bufferWindowRow2 = buffer2.getAs[Row](2)
          var bufferWindow2 = (bufferWindowRow2.getAs[Timestamp](0), bufferWindowRow2.getAs[Timestamp](1))

	  if( (!bufferWindow1._1.equals(new Timestamp(0))) && (!bufferWindow1._2.equals(new Timestamp(0))) && (!bufferWindow2._1.equals(new Timestamp(0))) && (!bufferWindow2._2.equals(new Timestamp(0)))  ){
	      buffer1(2) = (bufferWindow1._1, bufferWindow1._2)
	  }
	  else if( (!bufferWindow1._1.equals(new Timestamp(0))) && (!bufferWindow1._2.equals(new Timestamp(0))) && (bufferWindow2._1.equals(new Timestamp(0))) && (bufferWindow2._2.equals(new Timestamp(0))) ){
	     buffer1(2) = (bufferWindow1._1, bufferWindow1._2)
	  }
	  else {
	     buffer1(2) = (bufferWindow2._1, bufferWindow2._2)
 	  }
	}
	
     }
     

     // Calculates the final result
     def evaluate(buffer:Row): (Array[Tuple2[String,Long]], Array[String], (Timestamp, Timestamp)) = {

	val globalSummary = buffer.getAs[Seq[Row]](0).map{case Row(k: String, v: Long) => (k, v)}.toArray

	val clusters = buffer.getAs[Seq[String]](1).toArray

        val bufferWindowRow = buffer.getAs[Row](2)
        val bufferWindow = (bufferWindowRow.getAs[Timestamp](0), bufferWindowRow.getAs[Timestamp](1))

	(globalSummary, clusters, bufferWindow)
     }
   }
}


object MultiDataProducerMGSummary {

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

//		if ( timestampInMinutes >= currentTimeInMinutes ) Thread.sleep(3000)

		val timestampInMs = Calendar.getInstance().getTimeInMillis()
	        val formattedCurrentTime = dateFormat.format(timestampInMs)
//		val formattedCurrentTime = Calendar.getInstance().getTime()		

		val toSend = s"""{"when":"${formattedCurrentTime}","number":${Random.nextInt(20)},"producer_id":${rdd}}""".stripMargin
	        ks.value.send(topic, timestampInMs, Random.nextInt(2000).toString, toSend)
		timestampInMinutes = timestampInMinutes + 1

    	}

   })

 }
}
