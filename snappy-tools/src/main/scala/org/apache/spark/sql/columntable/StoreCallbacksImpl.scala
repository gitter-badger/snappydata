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
package org.apache.spark.sql.columntable


import java.util
import java.util.{Collections, UUID}

import com.gemstone.gemfire.internal.cache.{BucketRegion, PartitionedRegion}
import com.gemstone.gemfire.internal.snappy.{CallbackFactoryProvider, StoreCallbacks}
import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils
import com.pivotal.gemfirexd.internal.engine.store.{AbstractCompactExecRow, GemFireContainer}
import com.pivotal.gemfirexd.internal.iapi.error.StandardException
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext
import com.pivotal.gemfirexd.internal.iapi.store.access.{ScanController, TransactionController}
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection
import org.apache.spark.Logging
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.columnar.JDBCAppendableRelation
import org.apache.spark.sql.hive.SnappyStoreHiveCatalog
import org.apache.spark.sql.store.ExternalStore
import org.apache.spark.sql.types._

import scala.collection.{JavaConversions, mutable}

object StoreCallbacksImpl extends StoreCallbacks with Logging with Serializable {

  @transient private var sqlContext = None: Option[SQLContext]
  val stores = new mutable.HashMap[String, (StructType, ExternalStore, Int)]

  var useCompression = false
  var cachedBatchSize = 0

  def registerExternalStoreAndSchema(context: SQLContext, tableName: String,
      schema: StructType, externalStore: ExternalStore, batchSize: Int, compress : Boolean,
      rddId: Int) = {
    stores.synchronized {
      stores.get(tableName) match {
        case None => stores.put(tableName, (schema, externalStore, rddId))
        case Some((previousSchema, _, _)) => {
          if (previousSchema != schema) {
            stores.put(tableName, (schema, externalStore, rddId))
          }
        }
      }
    }
    sqlContext = Some(context)
    useCompression = compress
    cachedBatchSize = batchSize
  }

  override def createCachedBatch(region: BucketRegion, batchID: UUID, bucketID: Int) : java.util.Set[Any] = {
    val container: GemFireContainer = region.getPartitionedRegion.getUserAttribute.asInstanceOf[GemFireContainer]

    if (stores.get(container.getTableName) != None) {
      val (schema, externalStore, rddId) = stores.get(container.getTableName).get
      //LCC should be available assuming insert is already being done via a proper connection
      var conn: EmbedConnection = null
      var contextSet: Boolean = false
      try {
        var lcc: LanguageConnectionContext = Misc.getLanguageConnectionContext()
        if (lcc == null) {
          conn = GemFireXDUtils.getTSSConnection(true, true, false)
          conn.getTR.setupContextStack
          contextSet = true
          lcc = conn.getLanguageConnectionContext
          if (lcc == null) {
            Misc.getGemFireCache.getCancelCriterion.checkCancelInProgress(null)
          }
        }
        val row: AbstractCompactExecRow = container.newTemplateRow().asInstanceOf[AbstractCompactExecRow]
        lcc.setExecuteLocally(Collections.singleton(bucketID), region.getPartitionedRegion, false, null);
        try {
          val sc: ScanController = lcc.getTransactionExecute().openScan(container.getId().getContainerId(),
            false, 0, TransactionController.MODE_RECORD,
            TransactionController.ISOLATION_NOLOCK /* not used */ , null, null, 0, null, null, 0, null);

          val batchCreator = new CachedBatchCreator(
            ColumnFormatRelation.cachedBatchTableName(container.getTableName),
            container.getTableName, schema,
            externalStore, cachedBatchSize, useCompression)
          val keys = batchCreator.createAndStoreBatch(sc, row, batchID, bucketID)
          JavaConversions.mutableSetAsJavaSet(keys)
        }
        finally {
          lcc.setExecuteLocally(null, null, false, null);
        }
      }
      catch {
        case e : Throwable => throw e
      } finally {
        if (contextSet) {
          conn.getTR.restoreContextStack
        }
      }
    } else {
      new util.HashSet()
    }
  }

  def getInternalTableSchemas: util.List[String] = {
    val schemas = new util.ArrayList[String](2)
    schemas.add(SnappyStoreHiveCatalog.HIVE_METASTORE)
    schemas.add(ColumnFormatRelation.INTERNAL_SCHEMA_NAME)
    schemas
  }
}

trait StoreCallback extends Serializable {
  CallbackFactoryProvider.setStoreCallbacks(StoreCallbacksImpl)
}
