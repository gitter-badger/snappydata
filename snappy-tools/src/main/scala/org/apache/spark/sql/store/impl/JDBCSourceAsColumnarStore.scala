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
package org.apache.spark.sql.store.impl

import java.sql.{Connection, ResultSet, Statement}
import java.util.{Properties, UUID}

import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember
import com.gemstone.gemfire.internal.cache.{AbstractRegion, PartitionedRegion}
import com.pivotal.gemfirexd.internal.engine.Misc
import io.snappydata.SparkShellRDDHelper
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.collection._
import org.apache.spark.sql.columnar.{ExternalStoreUtils, CachedBatch, ConnectionProperties, ConnectionType}
import org.apache.spark.sql.rowtable.RowFormatScanRDD
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.store.{CachedBatchIteratorOnRS, ExternalStore, JDBCSourceAsStore, StoreUtils}
import org.apache.spark.sql.types.StructType
import org.apache.spark.storage.BlockManagerId
import org.apache.spark.{Logging, Partition, SparkContext, TaskContext}

import scala.reflect.ClassTag

/**
 * Column Store implementation for GemFireXD.
 */
final class JDBCSourceAsColumnarStore(_connProperties: ConnectionProperties,
    _numPartitions: Int,
    val blockMap: Map[InternalDistributedMember, BlockManagerId] =
    Map.empty[InternalDistributedMember, BlockManagerId])
    extends JDBCSourceAsStore(_connProperties, _numPartitions) {

  override def getCachedBatchRDD(tableName: String, requiredColumns: Array[String],
      sparkContext: SparkContext): RDD[CachedBatch] = {
    connectionType match {
      case ConnectionType.Embedded =>
        new ColumnarStorePartitionedRDD[CachedBatch](sparkContext,
          tableName, requiredColumns, this)
      case _ =>
        // remove the url property from poolProps since that will be
        // partition-specific
        val poolProps = _connProperties.poolProps -
            (if (_connProperties.hikariCP) "jdbcUrl" else "url")
        new SparkShellCachedBatchRDD[CachedBatch](sparkContext,
          tableName, requiredColumns, ConnectionProperties(_connProperties.url,
            _connProperties.driver, poolProps, _connProperties.connProps,
            _connProperties.hikariCP), this)
    }
  }

  override def getUUIDRegionKey(tableName: String, bucketId: Int = -1,
      batchId: Option[UUID] = None): UUIDRegionKey = {
    val connection: java.sql.Connection = getConnection(tableName)
    val uuid = connectionType match {
      case ConnectionType.Embedded =>
        val resolvedName = StoreUtils.lookupName(tableName, connection.getSchema)
        val region = Misc.getRegionForTable(resolvedName, true)
        region.asInstanceOf[AbstractRegion] match {
          case pr: PartitionedRegion =>
            if (bucketId == -1) {
              val primaryBucketIds = pr.getDataStore.
                getAllLocalPrimaryBucketIdArray
              genUUIDRegionKey(primaryBucketIds.getQuick(
                rand.nextInt(primaryBucketIds.size())), batchId.getOrElse(UUID.randomUUID))
            }
            else {
              genUUIDRegionKey(bucketId, batchId.getOrElse(UUID.randomUUID))
            }
          case _ =>
            genUUIDRegionKey()
        }
      case _ => genUUIDRegionKey(rand.nextInt(_numPartitions))
    }
    connection.close()
    uuid
  }
}

class ColumnarStorePartitionedRDD[T: ClassTag](@transient _sc: SparkContext,
    tableName: String,
    requiredColumns: Array[String], store: JDBCSourceAsColumnarStore)
    extends RDD[CachedBatch](_sc, Nil) with Logging {

  override def compute(split: Partition,
      context: TaskContext): Iterator[CachedBatch] = {
    store.tryExecute(tableName, {
      case conn =>
        val resolvedName = StoreUtils.lookupName(tableName, conn.getSchema)
        val par = split.index
        val ps1 = conn.prepareStatement(
          "call sys.SET_BUCKETS_FOR_LOCAL_EXECUTION(?, ?)")
        ps1.setString(1, resolvedName)
        ps1.setInt(2, par)
        ps1.execute()

        val ps = conn.prepareStatement("select " + requiredColumns.mkString(
          ", ") + ", numRows, stats from " + tableName)

        val rs = ps.executeQuery()
        ps1.close()
        new CachedBatchIteratorOnRS(conn, requiredColumns, ps, rs)
    }, closeOnSuccess = false)
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    split.asInstanceOf[MultiExecutorLocalPartition].hostExecutorIds
  }

  override protected def getPartitions: Array[Partition] = {
    store.tryExecute(tableName, {
      case conn =>
        val tableSchema = conn.getSchema
        StoreUtils.getPartitionsPartitionedTable(_sc, tableName, tableSchema, store.blockMap)
    })
  }
}

class SparkShellCachedBatchRDD[T: ClassTag](@transient _sc: SparkContext,
    tableName: String, requiredColumns: Array[String],
    connectionProperties: ConnectionProperties,
    store: ExternalStore)
    extends RDD[CachedBatch](_sc, Nil) {

  override def compute(split: Partition,
      context: TaskContext): Iterator[CachedBatch] = {
    val helper = new SparkShellRDDHelper
    val conn: Connection = helper.getConnection(connectionProperties, split)
    val query: String = helper.getSQLStatement(StoreUtils.lookupName(tableName, conn.getSchema),
      requiredColumns, split.index)
    val (statement, rs) = helper.executeQuery(conn, tableName, split, query)
    new CachedBatchIteratorOnRS(conn, requiredColumns, statement, rs)
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    split.asInstanceOf[ExecutorLocalShellPartition]
        .hostList.map(_._1.asInstanceOf[String]).toSeq
  }

  override def getPartitions: Array[Partition] = {
    SparkShellRDDHelper.getPartitions(tableName, store)
  }
}

class SparkShellRowRDD[T: ClassTag](@transient sc: SparkContext,
    getConnection: () => Connection,
    schema: StructType,
    tableName: String,
    columns: Array[String],
    connectionProperties: ConnectionProperties,
    store: ExternalStore,
    filters: Array[Filter] = Array.empty[Filter],
    partitions: Array[Partition] = Array.empty[Partition],
    blockMap: Map[InternalDistributedMember, BlockManagerId] =
    Map.empty[InternalDistributedMember, BlockManagerId],
    properties: Properties = new Properties())
    extends RowFormatScanRDD(sc, getConnection, schema, tableName, columns,
      connectionProperties, filters, partitions, blockMap, properties) {

  override def computeResultSet(
      thePart: Partition): (Connection, Statement, ResultSet) = {
    val helper = new SparkShellRDDHelper
    val conn: Connection = helper.getConnection(
      connectionProperties, thePart)
    val resolvedName = StoreUtils.lookupName(tableName, conn.getSchema)
    // TODO: this will fail if no network server is available unless SNAP-365 is
    // fixed with the approach of having an iterator that can fetch from remote
    val ps = conn.prepareStatement(
      "call sys.SET_BUCKETS_FOR_LOCAL_EXECUTION(?, ?)")
    ps.setString(1, resolvedName)
    ps.setInt(2, thePart.index)
    ps.executeUpdate()
    ps.close()

    val sqlText = s"SELECT $columnList FROM $resolvedName$filterWhereClause"
    val args = filterWhereArgs
    val stmt = conn.prepareStatement(sqlText)
    if (args ne null) {
      ExternalStoreUtils.setStatementParameters(stmt, args)
    }
    val fetchSize = properties.getProperty("fetchSize")
    if (fetchSize ne null) {
      stmt.setFetchSize(fetchSize.toInt)
    }

    val rs = stmt.executeQuery()
    (conn, stmt, rs)
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    split.asInstanceOf[ExecutorLocalShellPartition]
        .hostList.map(_._1.asInstanceOf[String]).toSeq
  }

  override def getPartitions: Array[Partition] = {
    SparkShellRDDHelper.getPartitions(tableName, store)
  }

  def getSQLStatement(resolvedTableName: String,
      requiredColumns: Array[String], partitionId: Int): String = {
    "select " + requiredColumns.mkString(", ") + " from " + resolvedTableName
  }
}
