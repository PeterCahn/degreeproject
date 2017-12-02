package com.htvu.producer

import java.util.Properties
import java.io.FileInputStream

import twitter4j.conf.ConfigurationBuilder

object TwitterProducerConfig {
  val props = new Properties()
  props.load(new FileInputStream("/home/pietro/thesis03/app.properties"))

  val KAFKA_TOPIC = props.getProperty("producer.topic")

  val producerProps = new Properties()
/*
  List(
    "serializer.class",
    "metadata.broker.list",
    "request.required.acks"
  ) foreach(s => producerProps.put(s, props.get(s)))
*/  
  producerProps.put("bootstrap.servers", props.getProperty("producer.brokers"))
  producerProps.put("serializer.class", "kafka.serializer.StringEncoder")
  producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
  producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
  producerProps.put("request.required.acks", "1")


  val cb = new ConfigurationBuilder()
  cb.setOAuthConsumerKey(props.getProperty("consumerKey"))
  cb.setOAuthConsumerSecret(props.getProperty("consumerSecret"))
  cb.setOAuthAccessToken(props.getProperty("accessToken"))
  cb.setOAuthAccessTokenSecret(props.getProperty("accessTokenSecret"))
  cb.setJSONStoreEnabled(true)
  cb.setIncludeEntitiesEnabled(true)

  val twitterStreamingConf = cb.build()
}
