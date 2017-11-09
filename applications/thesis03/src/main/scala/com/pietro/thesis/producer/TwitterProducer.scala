package com.htvu.producer

import kafka.producer.ProducerConfig
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import twitter4j.TwitterStreamFactory
import kafka.producer.KeyedMessage
import twitter4j._
import twitter4j.json._

import java.util.regex.Matcher
import java.util.regex.Pattern

import java.util.Date
import java.text.SimpleDateFormat
import java.util.TimeZone

import java.util.Properties
import java.io.FileInputStream

class TwitterStreamListener(twitterStream: TwitterStream) extends StatusListener  {
  this: StringKafkaProducer =>

//  val LOG = Logger.getLogger(classOf[TwitterStreamListener])

  def onStallWarning(stallWarning: StallWarning): Unit = {}

  def onDeletionNotice(statusDeletionNotice: StatusDeletionNotice): Unit = {}

  def onScrubGeo(l: Long, l1: Long): Unit = {}

  def onStatus(status: Status): Unit = {
//    if(status.getLang == "en"){
	val obj = new org.json.JSONObject(DataObjectFactory.getRawJSON(status))
	val text = obj.get("text").toString	
	val date = obj.get("created_at").toString
	val producerId = 1

	/* Convert created_at */
	val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
	dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+1"))
	
	val utilDate = new java.util.Date(date)
	val created_at = dateFormat.format(utilDate.getTime())	

	/* Find hashtags in text */
	val pattern = Pattern.compile("#\\w+")

	val matcher = pattern.matcher(text)
	while (matcher.find()){
	   val line  = s"""{"created_at":"${created_at}","value":"${matcher.group()}", "producer_id":"${producerId}"}"""
	   val msg = new ProducerRecord[String, String](TwitterProducerConfig.KAFKA_TOPIC, line)
	   producer.send(msg)
	}
//    }
  }

  def onTrackLimitationNotice(i: Int): Unit = {}

  def onException(e: Exception): Unit = {
//    LOG.info("Shutting down Twitter sample stream...")
    twitterStream.shutdown()
  }
}

trait StringKafkaProducer {
  val producer = new KafkaProducer[String, String]( TwitterProducerConfig.producerProps )
//  val producer = new Producer[String, String](new ProducerConfig(TwitterProducerConfig.producerProps))
}

class TwitterProducer {
  this: StringKafkaProducer =>

  def start(zones: Array[String]) = {
    val twitterStream = new TwitterStreamFactory(TwitterProducerConfig.twitterStreamingConf).getInstance()

    /* Get from zones file bounding box for parameter zone */
    val prop = new Properties()
    prop.load(new FileInputStream("zones.txt"))
    val a1 = prop.getProperty(zones(0)).split(" ")
    val a2 = prop.getProperty(zones(1)).split(" ")
    val a3 = prop.getProperty(zones(2)).split(" ")
    val a4 = prop.getProperty(zones(3)).split(" ")
    val boundingBoxProp = a1 ++ a2 ++ a3 ++ a4
    
    val boundingBox = boundingBoxProp.map(_.toDouble).grouped(2).toArray

    /* Filter query to track only tweets wich hashtags and from location within bounding box */
    val queryFilter = new FilterQuery().track(Array("#")).locations(boundingBox)

    val listener = new TwitterStreamListener(twitterStream) with StringKafkaProducer
    twitterStream.addListener(listener)

    twitterStream.filter(queryFilter)
  }
}
