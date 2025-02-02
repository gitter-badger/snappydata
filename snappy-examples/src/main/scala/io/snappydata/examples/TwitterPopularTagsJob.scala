package io.snappydata.examples

import java.io.PrintWriter

import com.typesafe.config.Config
import spark.jobserver.{SparkJobValid, SparkJobValidation}

import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.streaming.{SchemaDStream, SnappyStreamingJob}
import org.apache.spark.sql.types._
import org.apache.spark.streaming.dstream.DStream

/**
 * Run this on your local machine:
 *
 * `$ sbin/snappy-start-all.sh`
 *
 * To run with live twitter streaming, export twitter credentials
 * `$ export APP_PROPS="consumerKey=<consumerKey>,consumerSecret=<consumerSecret>, \
 * accessToken=<accessToken>,accessTokenSecret=<accessTokenSecret>"`
 *
 * `$ ./bin/snappy-job.sh submit --lead localhost:8090 \
 * --app-name TwitterPopularTagsJob --class io.snappydata.examples.TwitterPopularTagsJob \
 * --app-jar $SNAPPY_HOME/lib/quickstart-0.1.0-SNAPSHOT.jar --stream`
 *
 * To run with stored twitter data, run simulateTwitterStream after the Job is submitted:
 * `$ ./quickstart/scripts/simulateTwitterStream`
 */

object TwitterPopularTagsJob extends SnappyStreamingJob {

  override def runJob(snsc: C, jobConfig: Config): Any = {

    def getCurrentDirectory = new java.io.File(".").getCanonicalPath

    // scalastyle:off println
    var stream: DStream[_] = null
    var outFileName = s"TwitterPopularTagsJob-${System.currentTimeMillis}.out"
    val pw = new PrintWriter(outFileName)

    val schema = StructType(List(StructField("hashtag", StringType)))
    
    snsc.snappyContext.sql("DROP TABLE IF EXISTS topktable")
    snsc.snappyContext.sql("DROP TABLE IF EXISTS hashtagtable")
    snsc.snappyContext.sql("DROP TABLE IF EXISTS retweettable")

    if (jobConfig.hasPath("consumerKey") && jobConfig.hasPath("consumerKey")
      && jobConfig.hasPath("accessToken")  && jobConfig.hasPath("accessTokenSecret") ) {
      pw.println("##### Running example with live twitter stream #####")

      // Create twitter stream table
      snsc.sql("CREATE STREAM TABLE hashtagtable (hashtag STRING) USING " +
        "twitter_stream OPTIONS (" +
        s"consumerKey '${jobConfig.getString("consumerKey")}', " +
        s"consumerSecret '${jobConfig.getString("consumerSecret")}', " +
        s"accessToken '${jobConfig.getString("accessToken")}', " +
        s"accessTokenSecret '${jobConfig.getString("accessTokenSecret")}', " +
        "rowConverter 'org.apache.spark.sql.streaming.TweetToHashtagRow')")

      snsc.sql("CREATE STREAM TABLE retweettable (retweetId LONG, retweetCnt INT, " +
        "retweetTxt STRING) USING twitter_stream OPTIONS (" +
        s"consumerKey '${jobConfig.getString("consumerKey")}', " +
        s"consumerSecret '${jobConfig.getString("consumerSecret")}', " +
        s"accessToken '${jobConfig.getString("accessToken")}', " +
        s"accessTokenSecret '${jobConfig.getString("accessTokenSecret")}', " +
        "rowConverter 'org.apache.spark.sql.streaming.TweetToRetweetRow')")


    } else {
      // Create file stream table
      pw.println("##### Running example with stored tweet data #####")
      snsc.sql("CREATE STREAM TABLE hashtagtable (hashtag STRING) USING file_stream " +
        "OPTIONS (storagelevel 'MEMORY_AND_DISK_SER_2', " +
        "rowConverter 'org.apache.spark.sql.streaming.TweetToHashtagRow'," +
        "directory '/tmp/copiedtwitterdata')")

      snsc.sql("CREATE STREAM TABLE retweettable (retweetId LONG, retweetCnt INT, " +
        "retweetTxt STRING) USING file_stream " +
        "OPTIONS (storagelevel 'MEMORY_AND_DISK_SER_2', " +
        "rowConverter 'org.apache.spark.sql.streaming.TweetToRetweetRow'," +
        "directory '/tmp/copiedtwitterdata')")

    }

    // Register continuous queries on the tables and specify window clauses
    val retweetStream: SchemaDStream = snsc.registerCQ("SELECT * FROM retweettable " +
      "WINDOW (DURATION '2' SECONDS, SLIDE '2' SECONDS)")

    val topKOption = Map(
        "epoch" -> System.currentTimeMillis().toString,
        "timeInterval" -> "2000ms",
        "size" -> "10",
        "basetable" -> "hashtagtable"
      )

    // Create TopK table on the base stream table which is hashtagtable
    // TopK object is automatically populated from the stream table
    snsc.snappyContext.createApproxTSTopK("topktable", "hashtag",
      Some(schema), topKOption)

    val tableName = "retweetStore"

    snsc.snappyContext.dropTable(tableName, true )

    // Create row table to insert retweets based on retweetId as Primary key
    // When a tweet is retweeted multiple times, the previous entry of the tweet
    // is over written by the new retweet count.
    snsc.snappyContext.sql(s"CREATE TABLE $tableName (retweetId BIGINT PRIMARY KEY, " +
      s"retweetCnt INT, retweetTxt STRING) USING row OPTIONS ()")

    // Save data in snappy store
    retweetStream.foreachDataFrame(df => {
      df.write.mode(SaveMode.Append).saveAsTable(tableName)
    })

    snsc.start()

    // Iterate over the streaming data for twitter data and publish the results to a file.
    try {

      val runTime = if(jobConfig.hasPath("streamRunTime"))
      {
        jobConfig.getString("streamRunTime").toInt * 1000
      } else {
        120*1000
      }

      val end = System.currentTimeMillis + runTime
      while (end > System.currentTimeMillis()) {
        Thread.sleep(2000)
        pw.println("\n******** Top 10 hash tags of last two seconds *******\n")

        // Query the topk structure for the popular hashtags of last two seconds
        snsc.snappyContext.queryApproxTSTopK("topktable",
          System.currentTimeMillis - 2000,
          System.currentTimeMillis).collect().foreach {
          result => pw.println(result.toString())
        }

      }
      pw.println("\n************ Top 10 hash tags until now ***************\n")

      // Query the topk structure for the popular hashtags of until now
      snsc.sql("SELECT * FROM topktable").collect().foreach {
        result => pw.println(result.toString())
      }

      // Query the snappystore Row table to find out the top retweets
      pw.println("\n####### Top 10 popular tweets - Query Row table #######\n")
      snsc.snappyContext.sql(s"SELECT retweetId AS RetweetId, " +
        s"retweetCnt AS RetweetsCount, retweetTxt AS Text FROM ${tableName}" +
        s" ORDER BY RetweetsCount DESC LIMIT 10")
        .collect.foreach(row => {
        pw.println(row.toString())
      })

      pw.println("\n#######################################################")

    } finally {
      pw.close()

      snsc.stop(false, true)
    }
    // Return the output file name
    s"See ${getCurrentDirectory}/$outFileName"

    // scalastyle:on println
  }

  override def validate(snsc: C, config: Config): SparkJobValidation = {
    SparkJobValid
  }


}


