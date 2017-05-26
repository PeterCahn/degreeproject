import org.apache.spark._
import org.apache.spark.streaming._

object SimpleApp {
 def main(args: Array[String]) {

   val conf = new SparkConf().setMaster("local[2]").setAppName("NetworkWordCount")
   val ssc = new StreamingContext(conf, Seconds(1))
   val lines = ssc.socketTextStream("localhost",9999)
   val words = lines.flatMap(_.split(" "))
   val pairs = words.map(word => (word, 1))
   val wordCounts = pairs.reduceByKey(_ + _)
 
   // Print the first ten elements of each RDD generated in this DStream to the console
   wordCounts.print()

   ssc.start()
   ssc.awaitTermination()

//   println("DEVI SCRIVERE QUESTO\n")
//   val conf = new SparkConf().setAppName("Simple Application")
//   val sc = new SparkContext(conf)
//   val logData = sc.textFile(logFile, 2).cache()
//   val numAs = logData.filter(line => line.contains("a")).count()
//   val numBs = logData.filter(line => line.contains("b")).count()
//     println("Lines with a: %s, Lines with b: %s".format(numAs, numBs))

 }
}
