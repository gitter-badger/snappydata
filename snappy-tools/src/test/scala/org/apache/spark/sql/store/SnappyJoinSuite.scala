/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql.store

import io.snappydata.SnappyFunSuite
import io.snappydata.core.{FileCleaner, RefData, TestData2}
import org.scalatest.{BeforeAndAfterAll, FunSuite}

import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.joins.LocalJoin
import org.apache.spark.sql.execution.{Exchange, PartitionedPhysicalRDD, PhysicalRDD, QueryExecution}
import org.apache.spark.sql.{SaveMode, SnappyContext}
import org.apache.spark.{Logging, SparkContext}

class SnappyJoinSuite extends SnappyFunSuite with BeforeAndAfterAll {

  val props = Map.empty[String, String]

  test("Replicated table join with PR Table") {

    val rdd = sc.parallelize((1 to 5).map(i => RefData(i, s"$i")))
    val refDf = snc.createDataFrame(rdd)
    snc.sql("DROP TABLE IF EXISTS RR_TABLE")

    snc.sql("CREATE TABLE RR_TABLE(OrderRef INT NOT NULL,description String) USING row options()")

    refDf.write.format("row").mode(SaveMode.Append).options(props).saveAsTable("RR_TABLE")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE")

    val df = snc.sql("CREATE TABLE PR_TABLE(OrderId INT NOT NULL,description String, OrderRef INT)" +
        "USING row " +
        "options " +
        "(" +
        "PARTITION_BY 'OrderRef')")

    val dimension = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%5 + 1))))

    val dimensionDf = snc.createDataFrame(dimension)
    dimensionDf.write.format("row").mode(SaveMode.Append).options(props).saveAsTable("PR_TABLE")


    val countDf = snc.sql("select * from PR_TABLE P JOIN RR_TABLE R ON P.ORDERREF = R.ORDERREF")

    val qe = new QueryExecution(snc, countDf.logicalPlan)
    val plan = qe.executedPlan
    val lj = plan collectFirst {
      case lc : LocalJoin => lc
    }
    lj.getOrElse(sys.error(s"Can't find Local join in a 1 partitioned relation"))


    val t1 = System.currentTimeMillis()
    assert(countDf.count() === 1000) // Make sure aggregation is working with local join
    val t2 = System.currentTimeMillis()
    println("Time taken = "+ (t2-t1))

    val projectDF = snc.sql("select ORDERID, P.DESCRIPTION , R.DESCRIPTION from PR_TABLE P JOIN RR_TABLE R ON P.ORDERREF = R.ORDERREF")
    assert(projectDF.columns.length === 3)

    val sumDF = snc.sql("select SUM(ORDERID)from PR_TABLE P JOIN RR_TABLE R ON P.ORDERREF = R.ORDERREF")
    assert(sumDF.collect()(0).getLong(0) === ((1000 * 1001)/2))

  }

  test("Replicated table join with Replicated Table") {

    val rdd = sc.parallelize((1 to 5).map(i => RefData(i, s"$i")))
    val refDf = snc.createDataFrame(rdd)
    snc.sql("DROP TABLE IF EXISTS RR_TABLE1")

    snc.sql("CREATE TABLE RR_TABLE1(OrderRef INT NOT NULL,description String) USING row options()")


    refDf.write.format("row").mode(SaveMode.Append).options(props).saveAsTable("RR_TABLE1")

    snc.sql("DROP TABLE IF EXISTS RR_TABLE2")

    val df = snc.sql("CREATE TABLE RR_TABLE2(OrderId INT NOT NULL,description String, OrderRef INT) USING row options()" )


    val dimension = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%5 + 1))))

    val dimensionDf = snc.createDataFrame(dimension)
    dimensionDf.write.format("row").mode(SaveMode.Append).options(props).saveAsTable("RR_TABLE2")


    val countDf = snc.sql("select * from RR_TABLE1 P JOIN RR_TABLE2 R ON P.ORDERREF = R.ORDERREF")
    val qe = new QueryExecution(snc, countDf.logicalPlan)

    val lj = qe.executedPlan collectFirst {
      case lc : LocalJoin => lc
    }
    lj.getOrElse(sys.error(s"Can't find Local join in a 1 partitioned relation"))

    val t1 = System.currentTimeMillis()
    assert(countDf.count() === 1000) // Make sure aggregation is working with local join
    val t2 = System.currentTimeMillis()
    println("Time taken = "+ (t2-t1))

    val projectDF = snc.sql("select R.ORDERID, P.DESCRIPTION , R.DESCRIPTION from RR_TABLE1 P JOIN RR_TABLE2 R ON P.ORDERREF = R.ORDERREF")
    assert(projectDF.columns.length === 3)

    val sumDF = snc.sql("select SUM(R.ORDERID)from RR_TABLE1 P JOIN RR_TABLE2 R ON P.ORDERREF = R.ORDERREF")
    assert(sumDF.collect()(0).getLong(0) === ((1000 * 1001)/2))

  }

  /**
   * This method is very specific to  PartitionedDataSourceScan and snappy join improvements
   *
   */
  private def checkForShuffle(plan :LogicalPlan, snc : SnappyContext, shuffleExpected : Boolean): Unit ={

    val qe = new QueryExecution(snc, plan)
    //println(qe.executedPlan)
    val lj = qe.executedPlan collect {
      case ex : Exchange => ex
    }
    if(shuffleExpected){
      if(lj.length == 0) sys.error(s"Shuffle Expected , but was not found")
    }else{
      lj.foreach(a => a.child.collect { // this means no Exhange should have child as PartitionedPhysicalRDD
        case p : PartitionedPhysicalRDD => sys.error(s"Did not expect exchange with partitioned scan with same partitions")
        case p : PhysicalRDD => sys.error(s"Did not expect PhyscialRDD with PartitionedDataSourceScan")
        case _ => // do nothing, may be some other Exchange and not with scan
      })
    }
  }

  test("Row PR table join with PR Table") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%10 + 1))))
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE1")

    snc.sql("CREATE TABLE PR_TABLE1(OrderId INT NOT NULL,description String, OrderRef INT)" +
        "USING row " +
        "options " +
        "(" +
        "PARTITION_BY 'OrderId, OrderRef')")


    refDf.write.format("row").mode(SaveMode.Append).options(props).saveAsTable("PR_TABLE1")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE2")

    snc.sql("CREATE TABLE PR_TABLE2(OrderId INT NOT NULL,description String, OrderRef INT)" +
        "USING row " +
        "options " +
        "(" +
        "PARTITION_BY 'OrderId,OrderRef')")

    val dimension2 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%5 + 1))))

    val dimensionDf = snc.createDataFrame(dimension2)
    dimensionDf.write.format("row").mode(SaveMode.Append).options(props).saveAsTable("PR_TABLE2")

    partitionToPartitionJoinAssertions(snc, "PR_TABLE1", "PR_TABLE2")

  }

  test("Column PR table join with PR Table") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%10 + 1))))
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE3")

    snc.sql("CREATE TABLE PR_TABLE3(OrderId INT, description String, OrderRef INT)" +
        "USING column " +
        "options " +
        "(" +
        "PARTITION_BY 'OrderId,OrderRef')")


    refDf.write.format("column").mode(SaveMode.Append).options(props).saveAsTable("PR_TABLE3")

    val countdf = snc.sql("select * from PR_TABLE3")
    assert(countdf.count() == 1000)

    snc.sql("DROP TABLE IF EXISTS PR_TABLE4")

    snc.sql("CREATE TABLE PR_TABLE4(OrderId INT ,description String, OrderRef INT)" +
        "USING column " +
        "options " +
        "(" +
        "PARTITION_BY 'OrderId,OrderRef')")

    val dimension2 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%5 + 1))))

    val dimensionDf = snc.createDataFrame(dimension2)
    dimensionDf.write.format("column").mode(SaveMode.Append).options(props).saveAsTable("PR_TABLE4")
    val countdf1 = snc.sql("select * from PR_TABLE4")
    assert(countdf1.count() == 1000)
    partitionToPartitionJoinAssertions(snc, "PR_TABLE3", "PR_TABLE4")

  }

  test("Column PR table join with Non user mentioned PR Table") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%10 + 1))))
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE5")

    snc.sql("CREATE TABLE PR_TABLE5(OrderId INT, description String, OrderRef INT)" +
        "USING column " +
        "options " +
        "(" +
        "PARTITION_BY 'OrderId, OrderRef')")


    refDf.write.format("column").mode(SaveMode.Append).options(props).saveAsTable("PR_TABLE5")

    val countdf = snc.sql("select * from PR_TABLE5")
    assert(countdf.count() == 1000)

    snc.sql("DROP TABLE IF EXISTS PR_TABLE6")

    snc.sql("CREATE TABLE PR_TABLE6(OrderId INT ,description String, OrderRef INT)" +
        "USING column options()")

    val dimension2 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%5 + 1))))

    val dimensionDf = snc.createDataFrame(dimension2)
    dimensionDf.write.format("column").mode(SaveMode.Append).options(props).saveAsTable("PR_TABLE6")
    val countdf1 = snc.sql("select * from PR_TABLE6")
    assert(countdf1.count() == 1000)
    val excatJoinKeys = snc.sql(s"select P.OrderRef, P.description from " +
        s"PR_TABLE5 P JOIN PR_TABLE6 R ON P.OrderId = R.OrderId AND P.OrderRef = R.OrderRef")
    checkForShuffle(excatJoinKeys.logicalPlan, snc, true)

  }

  test("Column PR table join with Row PR Table") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%10 + 1))))
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE7")

    snc.sql("CREATE TABLE PR_TABLE7(OrderId INT, description String, OrderRef INT)" +
        "USING row " +
        "options " +
        "(" +
        "PARTITION_BY 'OrderId, OrderRef')")


    refDf.write.format("column").mode(SaveMode.Append).options(props).saveAsTable("PR_TABLE7")

    val countdf = snc.sql("select * from PR_TABLE7")
    assert(countdf.count() == 1000)

    snc.sql("DROP TABLE IF EXISTS PR_TABLE8")

    snc.sql("CREATE TABLE PR_TABLE8(OrderId INT ,description String, OrderRef INT)" +
        "USING column " +
        "options " +
        "(" +
        "PARTITION_BY 'OrderId, OrderRef')")


    val dimension2 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%5 + 1))))

    val dimensionDf = snc.createDataFrame(dimension2)
    dimensionDf.write.format("column").mode(SaveMode.Append).options(props).saveAsTable("PR_TABLE8")
    val countdf1 = snc.sql("select * from PR_TABLE8")
    assert(countdf1.count() == 1000)
    val excatJoinKeys = snc.sql(s"select P.ORDERREF, P.DESCRIPTION from " +
        s"PR_TABLE7 P JOIN PR_TABLE8 R ON P.ORDERID = R.OrderId AND P.ORDERREF = R.OrderRef")
   checkForShuffle(excatJoinKeys.logicalPlan, snc, false)
    assert(excatJoinKeys.count() === 500)

  }

  test("Row PR table join with PR Table with unequal partitions") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%10 + 1))))
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE9")

    snc.sql("CREATE TABLE PR_TABLE9(OrderId INT NOT NULL,description String, OrderRef INT)" +
        "USING row " +
        "options " +
        "(" +
        "PARTITION_BY 'OrderId, OrderRef')")


    refDf.write.format("row").mode(SaveMode.Append).options(props).saveAsTable("PR_TABLE9")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE10")

    snc.sql("CREATE TABLE PR_TABLE10(OrderId INT NOT NULL,description String, OrderRef INT)" +
        "USING row " +
        "options " +
        "(" +
        "PARTITION_BY 'OrderId,OrderRef'," +
        "BUCKETS '213')")

    val dimension2 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%5 + 1))))

    val dimensionDf = snc.createDataFrame(dimension2)
    dimensionDf.write.format("row").mode(SaveMode.Append).options(props).saveAsTable("PR_TABLE10")
    val excatJoinKeys = snc.sql(s"select P.ORDERREF, P.DESCRIPTION from " +
        s"PR_TABLE9 P JOIN PR_TABLE10 R ON P.ORDERID = R.OrderId AND P.ORDERREF = R.OrderRef")
    checkForShuffle(excatJoinKeys.logicalPlan, snc, true)
    assert(excatJoinKeys.count() === 500)

  }

  test("More than two table joins") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%10 + 1))))
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE11")

    snc.sql("CREATE TABLE PR_TABLE11(OrderId INT NOT NULL,description String, OrderRef INT)" +
        "USING row " +
        "options " +
        "(" +
        "PARTITION_BY 'OrderId, OrderRef')")


    refDf.write.format("row").mode(SaveMode.Append).options(props).saveAsTable("PR_TABLE11")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE12")

    snc.sql("CREATE TABLE PR_TABLE12(OrderId INT NOT NULL,description String, OrderRef INT)" +
        "USING row " +
        "options " +
        "(" +
        "PARTITION_BY 'OrderId,OrderRef')")

    val dimension2 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%5 + 1))))

    val dimensionDf2 = snc.createDataFrame(dimension2)
    dimensionDf2.write.format("row").mode(SaveMode.Append).options(props).saveAsTable("PR_TABLE12")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE13")

    snc.sql("CREATE TABLE PR_TABLE13(OrderId INT NOT NULL,description String, OrderRef INT)" +
        "USING row " +
        "options " +
        "(" +
        "PARTITION_BY 'OrderId,OrderRef'," +
        "BUCKETS '213')")

    val dimension3 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, (i%5 + 1))))

    val dimensionDf3 = snc.createDataFrame(dimension3)
    dimensionDf3.write.format("row").mode(SaveMode.Append).options(props).saveAsTable("PR_TABLE13")


    val excatJoinKeys = snc.sql(s"select P.ORDERREF, P.DESCRIPTION from " +
        s"PR_TABLE11 P ,PR_TABLE12 R, PR_TABLE13 Q where" +
        s" P.ORDERID = R.OrderId AND P.ORDERREF = R.OrderRef " +
        s"AND " +
        s"R.ORDERID = Q.OrderId AND R.ORDERREF = Q.OrderRef")
    checkForShuffle(excatJoinKeys.logicalPlan, snc, true)
    assert(excatJoinKeys.count() === 500)

  }


  def partitionToPartitionJoinAssertions(snc : SnappyContext, t1 : String , t2 : String): Unit ={
    val excatJoinKeys = snc.sql(s"select P.OrderRef, P.description from " +
        s"$t1 P JOIN $t2 R ON P.OrderId = R.OrderId AND P.OrderRef = R.OrderRef")
    checkForShuffle(excatJoinKeys.logicalPlan, snc, false)
    assert(excatJoinKeys.count() === 500) // Make sure aggregation is working with non-shuffled joins

    // Reverse the join keys
    val reverseJoinKeys = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P JOIN $t2 R ON P.OrderRef = R.OrderRef " +
        "AND P.OrderId = R.OrderId")
    checkForShuffle(reverseJoinKeys.logicalPlan, snc, false)

    // Partial join keys
    val partialJoinKeys = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P JOIN $t2 R ON P.OrderRef = R.OrderRef")
    checkForShuffle(partialJoinKeys.logicalPlan, snc, true)

    // More join keys than partitioning keys
    val moreJoinKeys = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P JOIN $t2 R ON P.OrderRef = R.OrderRef AND " +
        "P.OrderId = R.OrderId AND P.description = R.description")
    checkForShuffle(moreJoinKeys.logicalPlan, snc, true)


    val leftSemijoinDF = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P LEFT SEMI JOIN $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    checkForShuffle(leftSemijoinDF.logicalPlan, snc, false) // We don't expect a shuffle here
    assert(leftSemijoinDF.count() === 500)

    val innerJoinDF = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P INNER JOIN $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    checkForShuffle(innerJoinDF.logicalPlan, snc, false) // We don't expect a shuffle here
    assert(innerJoinDF.count() === 500)

    val leftJoinDF = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P LEFT JOIN $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    checkForShuffle(leftJoinDF.logicalPlan, snc, false) // We don't expect a shuffle here
    assert(leftJoinDF.count() == 1000)

    val rightJoinDF = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P RIGHT JOIN $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    checkForShuffle(rightJoinDF.logicalPlan, snc, false) // We don't expect a shuffle here
    assert(rightJoinDF.count() == 1000)

    val leftOuterJoinDF = snc.sql("select P.OrderRef, P.description " +
        s"from $t1 P LEFT OUTER JOIN $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    checkForShuffle(leftOuterJoinDF.logicalPlan, snc, false) // We don't expect a shuffle here
    assert(leftOuterJoinDF.count() == 1000)

    val rightOuterJoinDF = snc.sql("select P.OrderRef, P.description " +
        s"from $t1 P RIGHT OUTER JOIN $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    checkForShuffle(rightOuterJoinDF.logicalPlan, snc, false) // We don't expect a shuffle here
    assert(rightOuterJoinDF.count() == 1000)

    val fullJoinDF = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P FULL JOIN $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    checkForShuffle(fullJoinDF.logicalPlan, snc, false) // We don't expect a shuffle here
    assert(fullJoinDF.count() == 1500)

    val fullOuterJoinDF = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P FULL OUTER JOIN   $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    checkForShuffle(fullOuterJoinDF.logicalPlan, snc, false) // We don't expect a shuffle here
    assert(fullOuterJoinDF.count() == 1500)
  }

}
