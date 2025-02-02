package io.snappydata.examples

import org.apache.spark.sql.{Row, SnappyContext, DataFrame}
import org.apache.spark.{SparkContext, SparkConf}

/**
  * This application depicts how a Spark cluster can
  * connect to a Snappy cluster to fetch and query the tables
  * using Scala APIs in a Spark App.
  */
object AirlineDataSparkApp {

  def main(args: Array[String]) {
    // scalastyle:off println
    val conf = new SparkConf().
      setAppName("Airline Data Application")

    val sc = new SparkContext(conf)
    val snc = SnappyContext(sc)
    snc.sql("set spark.sql.shuffle.partitions=5")

    val colTableName = "airline"
    val rowTableName = "airlineref"

    // Get the tables that were created using sql scripts via snappy-shell
    val airlineDF: DataFrame = snc.table(colTableName)
    val airlineCodeDF: DataFrame = snc.table(rowTableName)

    // Data Frame query :Which Airlines Arrive On Schedule? JOIN with reference table
    val colResult = airlineDF.join(airlineCodeDF, airlineDF.col("UniqueCarrier").
        equalTo(airlineCodeDF("CODE"))).groupBy(airlineDF("UniqueCarrier"),
      airlineCodeDF("DESCRIPTION")).agg("ArrDelay" -> "avg").orderBy("avg(ArrDelay)")
    println("Airline arrival schedule")
    val start = System.currentTimeMillis
    colResult.show
    val totalTimeCol = (System.currentTimeMillis - start)
    println(s"Query time:${totalTimeCol}ms\n")

    // Suppose a particular Airline company say 'Delta Air Lines Inc.'
    // re-brands itself as 'Delta America'.Update the row table.
    val query: String = " CODE ='DL'"
    val newColumnValues: Row = Row("Delta America")
    snc.update(rowTableName,query,newColumnValues,"DESCRIPTION")

    // Data Frame query :Which Airlines Arrive On Schedule? JOIN with reference table
    val colResultAftUpd = airlineDF.join(airlineCodeDF, airlineDF.col("UniqueCarrier").
        equalTo(airlineCodeDF("CODE"))).groupBy(airlineDF("UniqueCarrier"),
      airlineCodeDF("DESCRIPTION")).agg("ArrDelay" -> "avg").orderBy("avg(ArrDelay)")
    println("Airline arrival schedule after Updated values:")
    val startColUpd = System.currentTimeMillis
    colResultAftUpd.show
    val totalTimeColUpd = (System.currentTimeMillis - startColUpd)
    println(s" Query time:${totalTimeColUpd}ms")
    // scalastyle:on println
  }

}
