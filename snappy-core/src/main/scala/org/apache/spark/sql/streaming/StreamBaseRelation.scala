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
package org.apache.spark.sql.streaming

import scala.collection.mutable

import org.apache.spark.Logging
import org.apache.spark.rdd.{EmptyRDD, RDD}
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.{CatalystTypeConverters, InternalRow}
import org.apache.spark.sql.execution.datasources.DDLException
import org.apache.spark.sql.hive.SnappyStoreHiveCatalog
import org.apache.spark.sql.sources._
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.Time
import org.apache.spark.streaming.dstream.{DStream, InputDStream, ReceiverInputDStream}
import org.apache.spark.util.Utils

abstract class StreamBaseRelation(options: Map[String, String])
    extends ParentRelation with StreamPlan with TableScan
    with DestroyRelation with Serializable with Logging {

  final def context = SnappyStreamingContext.getActive.getOrElse(
    throw new IllegalStateException("No active streaming context"))

  @transient val tableName = options(JdbcExtendedUtils.DBTABLE_PROPERTY)

  override def addDependent(dependent: DependentRelation,
      catalog: SnappyStoreHiveCatalog): Boolean =
    DependencyCatalog.addDependent(tableName, dependent.name)

  override def removeDependent(dependent: DependentRelation,
      catalog: SnappyStoreHiveCatalog): Boolean =
    DependencyCatalog.removeDependent(tableName, dependent.name)

  override def getDependents(catalog: SnappyStoreHiveCatalog): Seq[String] =
    DependencyCatalog.getDependents(tableName)

  val storageLevel = options.get("storageLevel")
      .map(StorageLevel.fromString)
      .getOrElse(StorageLevel.MEMORY_AND_DISK_SER_2)

  val rowConverter = {
    try {
      val clz = Utils.getContextOrSparkClassLoader.loadClass(options("rowConverter"))
      clz.newInstance().asInstanceOf[StreamToRowsConverter]
    } catch {
      case e: Exception => sys.error(s"Failed to load class : ${e.toString}")
    }
  }

  protected def createRowStream(): DStream[InternalRow]

  @transient override final lazy val rowStream: DStream[InternalRow] =
    StreamBaseRelation.getOrCreateRowStream(tableName, { () =>
      val stream = createRowStream()
      // search for existing dependents in the catalog (these may still not
      //   have been initialized e.g. after recovery, so add explicitly)
      val catalog = context.snappyContext.catalog
      val initDependents = catalog.getDataSourceTables(Seq.empty,
        Some(tableName)).map(_.toString)
      (stream, initDependents)
    })

  override def buildScan(): RDD[Row] = {
    val converter = CatalystTypeConverters.createToScalaConverter(schema)
    if (rowStream.generatedRDDs.isEmpty) {
      new EmptyRDD[Row](sqlContext.sparkContext)
    } else {
      rowStream.generatedRDDs.maxBy(_._1)._2.map(converter(_)).asInstanceOf[RDD[Row]]
    }

    /* val currentRDD = rowStream.generatedRDDs.maxBy(_._1)._2
      val rdds = Seq(rowStream.generatedRDDs.values.toArray: _*)
      val urdds = new UnionRDD(sqlContext.sparkContext, rdds)
      urdds.map(converter(_)).asInstanceOf[RDD[Row]]
    } */
  }

  override def destroy(ifExists: Boolean): Unit = {
    StreamBaseRelation.removeStream(tableName).foreach(
      StreamBaseRelation.stopStream)
    DependencyCatalog.removeAllDependents(tableName)
  }

  def truncate(): Unit = {
    throw new DDLException("Stream tables cannot be truncated")
  }
}

private object StreamBaseRelation extends Logging {

  private[this] val tableToStream =
    new mutable.HashMap[String, DStream[InternalRow]]()

  private[this] val LOCK = new Object()

  var validTime: Time = null

  def setValidTime(time: Time): Unit = {
    if (validTime == null) {
      validTime = time
    } else if (validTime != time) {
      validTime = time
    } else {
    }
  }

  private def getOrCreateRowStream(tableName: String, createStream: () =>
      (DStream[InternalRow], Seq[String])): DStream[InternalRow] =
    LOCK.synchronized {
      tableToStream.get(tableName) match {
        case None =>
          val (stream, dependents) = createStream()
          tableToStream += (tableName -> stream)
          DependencyCatalog.addDependents(tableName, dependents)
          stream
        case Some(stream) => stream
      }
    }

  private def removeStream(tableName: String): Option[DStream[InternalRow]] =
    LOCK.synchronized {
      tableToStream.remove(tableName)
    }

  def stopStream(stream: DStream[_]): Unit = stream match {
    case inputStream: ReceiverInputDStream[_] =>
      try {
        val receiver = inputStream.getReceiver()
        if (receiver != null && !receiver.isStopped()) {
          receiver.stop("destroyRelation")
        }
      } finally {
        inputStream.stop()
      }
    case inputStream: InputDStream[_] => inputStream.stop()
    case _ => // nothing
  }

  private[streaming] def clearStreams(): Unit = LOCK.synchronized {
    tableToStream.clear()
  }
}
