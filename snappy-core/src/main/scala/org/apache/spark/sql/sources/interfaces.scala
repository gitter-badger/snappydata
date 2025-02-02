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
package org.apache.spark.sql.sources

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.hive.SnappyStoreHiveCatalog
import org.apache.spark.sql.{DataFrame, Row, SQLContext, SaveMode}

@DeveloperApi
trait RowInsertableRelation extends SingleRowInsertableRelation {

  /**
   * Insert a sequence of rows into the table represented by this relation.
   *
   * @param rows the rows to be inserted
   *
   * @return number of rows inserted
   */
  def insert(rows: Seq[Row]): Int
}

trait RowPutRelation extends SingleRowInsertableRelation {
  /**
   * If the row is already present, it gets updated otherwise it gets
   * inserted into the table represented by this relation
   *
   * @param rows the rows to be upserted
   *
   * @return number of rows upserted
   */

  def put(rows: Seq[Row]): Int

  /**
   * If the row is already present, it gets updated otherwise it gets
   * inserted into the table represented by this relation
   *
   * @param df the <code>DataFrame</code> to be upserted
   *
   */
  def put(df: DataFrame): Unit
}

@DeveloperApi
trait SingleRowInsertableRelation {
  /**
   * Execute a DML SQL and return the number of rows affected.
   */
  def executeUpdate(sql: String): Int
}

/**
 * ::DeveloperApi
 *
 * An extension to <code>InsertableRelation</code> that allows for data to be
 * inserted (possibily having different schema) into the target relation after
 * comparing against the result of <code>insertSchema</code>.
 */
@DeveloperApi
trait SchemaInsertableRelation extends InsertableRelation {

  /**
   * Return the schema required for insertion into the relation
   * or None if <code>sourceSchema</code> cannot be inserted.
   */
  def schemaForInsert(sourceSchema: Seq[Attribute]): Option[Seq[Attribute]]

  /**
   * Append a given RDD or rows into the relation.
   */
  def append(rows: RDD[Row], time: Long = -1): Unit
}

/**
 * A relation having a parent-child relationship with a base relation.
 */
@DeveloperApi
trait DependentRelation extends BaseRelation {

  /** Base table of this relation. */
  def baseTable: Option[String]

  /** Name of this relation in the catalog. */
  def name: String
}

/**
 * A relation having a parent-child relationship with one or more
 * <code>DependentRelation</code>s as children.
 */
@DeveloperApi
trait ParentRelation extends BaseRelation {

  /** Used by <code>DependentRelation</code>s to register with parent */
  def addDependent(dependent: DependentRelation,
      catalog: SnappyStoreHiveCatalog): Boolean

  /** Used by <code>DependentRelation</code>s to unregister with parent */
  def removeDependent(dependent: DependentRelation,
      catalog: SnappyStoreHiveCatalog): Boolean

  /** Get the dependent child relations. */
  def getDependents(catalog: SnappyStoreHiveCatalog): Seq[String]
}

@DeveloperApi
trait SamplingRelation extends DependentRelation with SchemaInsertableRelation {

  /**
   * Options set for this sampling relation.
   */
  def samplingOptions: Map[String, Any]

  /**
   * The QCS columns for the sample.
   */
  def qcs: Array[String]
}

@DeveloperApi
trait UpdatableRelation extends SingleRowInsertableRelation {

  /**
   * Update a set of rows matching given criteria.
   *
   * @param filterExpr SQL WHERE criteria to select rows that will be updated
   * @param newColumnValues updated values for the columns being changed;
   *                        must match `updateColumns`
   * @param updateColumns the columns to be updated; must match `updatedColumns`
   *
   * @return number of rows affected
   */
  def update(filterExpr: String, newColumnValues: Row,
      updateColumns: Seq[String]): Int
}

@DeveloperApi
trait DeletableRelation {

  /**
   * Delete a set of row matching given criteria.
   *
   * @param filterExpr SQL WHERE criteria to select rows that will be deleted
   *
   * @return number of rows deleted
   */
  def delete(filterExpr: String): Int
}

@DeveloperApi
trait DestroyRelation {

  /**
   * Truncate the table represented by this relation.
   */
  def truncate(): Unit

  /**
   * Destroy and cleanup this relation. It may include, but not limited to,
   * dropping the external table that this relation represents.
   */
  def destroy(ifExists: Boolean): Unit
}

@DeveloperApi
trait IndexableRelation {

  /**
   * Execute index on the table.
   */
  def createIndex(tableName: String, colName: String): Unit
}

/**
 * ::DeveloperApi::
 * Implemented by objects that produce relations for a specific kind of data
 * source with a given schema.  When Spark SQL is given a DDL operation with
 * a USING clause specified (to specify the implemented SchemaRelationProvider)
 * and a user defined schema, this interface is used to pass in the parameters
 * specified by a user.
 *
 * Users may specify the fully qualified class name of a given data source.
 * When that class is not found Spark SQL will append the class name
 * `DefaultSource` to the path, allowing for less verbose invocation.
 * For example, 'org.apache.spark.sql.json' would resolve to the data source
 * 'org.apache.spark.sql.json.DefaultSource'.
 *
 * A new instance of this class with be instantiated each time a DDL call is made.
 *
 * The difference between a [[SchemaRelationProvider]] and an
 * [[ExternalSchemaRelationProvider]] is that latter accepts schema and other
 * clauses in DDL string and passes over to the backend as is, while the schema
 * specified for former is parsed by Spark SQL.
 * A relation provider can inherit both [[SchemaRelationProvider]] and
 * [[ExternalSchemaRelationProvider]] if it can support both Spark SQL schema
 * and backend-specific schema.
 */
@DeveloperApi
trait ExternalSchemaRelationProvider {
  /**
   * Returns a new base relation with the given parameters and user defined
   * schema (and possibly other backend-specific clauses).
   * Note: the parameters' keywords are case insensitive and this insensitivity
   * is enforced by the Map that is passed to the function.
   */
  def createRelation(
      sqlContext: SQLContext,
      mode: SaveMode,
      parameters: Map[String, String],
      schema: String): BaseRelation
}
