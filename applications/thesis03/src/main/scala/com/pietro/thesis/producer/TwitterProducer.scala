package com.htvu.producer

import kafka.producer.ProducerConfig
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import twitter4j.TwitterStreamFactory
import kafka.producer.KeyedMessage
import twitter4j._

import java.util.regex.Matcher
import java.util.regex.Pattern

import java.util.Date
import java.text.SimpleDateFormat
import java.util.TimeZone

class TwitterStreamListener(twitterStream: TwitterStream) extends StatusListener  {
  this: StringKafkaProducer =>

  val LOG = Logger.getLogger(classOf[TwitterStreamListener])

  def onStallWarning(stallWarning: StallWarning): Unit = {}

  def onDeletionNotice(statusDeletionNotice: StatusDeletionNotice): Unit = {}

  def onScrubGeo(l: Long, l1: Long): Unit = {}

  def onStatus(status: Status): Unit = {
    if(status.getLang == "en"){
	val obj = new twitter4j.JSONObject(TwitterObjectFactory.getRawJSON(status))
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
    }
  }

  def onTrackLimitationNotice(i: Int): Unit = {}

  def onException(e: Exception): Unit = {
    LOG.info("Shutting down Twitter sample stream...")
    twitterStream.shutdown()
  }
}

trait StringKafkaProducer {
  val producer = new KafkaProducer[String, String]( TwitterProducerConfig.producerProps )
//  val producer = new Producer[String, String](new ProducerConfig(TwitterProducerConfig.producerProps))
}

class TwitterProducer {
  this: StringKafkaProducer =>

  def start() = {
    val twitterStream = new TwitterStreamFactory(TwitterProducerConfig.twitterStreamingConf).getInstance()
    val queryFilter = new FilterQuery().language("en").track("#")

    val listener = new TwitterStreamListener(twitterStream) with StringKafkaProducer
    twitterStream.addListener(listener)

    twitterStream.filter(queryFilter)
  }
}
