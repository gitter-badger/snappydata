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

import java.sql.Connection
import java.util.Properties

import scala.util.control.NonFatal

import org.apache.spark.sql.execution.datasources.{CaseInsensitiveMap, ResolvedDataSource}
import org.apache.spark.sql.jdbc.JdbcDialect
import org.apache.spark.sql.types._
import org.apache.spark.sql.{AnalysisException, SQLContext, SaveMode}

/**
 * Some extensions to `JdbcDialect` used by Snappy implementation.
 */
abstract class JdbcExtendedDialect extends JdbcDialect {

  /** Query string to check for existence of a table */
  def tableExists(tableName: String, conn: Connection,
      context: SQLContext): Boolean =
    JdbcExtendedUtils.tableExistsInMetaData(tableName, conn, this)

  /** Get the current schema set on the given connection. */
  def getCurrentSchema(conn: Connection): String = conn.getSchema

  /** Get the current schema set on the given connection. */
  def createSchema(schemaName: String, conn: Connection): Unit

  /** DDL to truncate a table, or null/empty if truncate is not supported */
  def truncateTable(tableName: String): String = s"TRUNCATE TABLE $tableName"

  def dropTable(tableName: String, conn: Connection, context: SQLContext,
      ifExists: Boolean): Unit

  def initializeTable(tableName: String, caseSensitive: Boolean,
      conn: Connection): Unit = {
  }

  def extraDriverProperties(isLoner: Boolean): Properties =
    new Properties()

  def getPartitionByClause(col: String): String
}

object JdbcExtendedUtils {

  val DBTABLE_PROPERTY = "dbtable"
  val SCHEMA_PROPERTY = "schemaddl"
  val ALLOW_EXISTING_PROPERTY = "allowexisting"
  val BASETABLE_PROPERTY = "basetable"

  val TABLETYPE_PROPERTY = "EXTERNAL"

  def executeUpdate(sql: String, conn: Connection): Unit = {
    val stmt = conn.createStatement()
    try {
      stmt.executeUpdate(sql)
    } finally {
      stmt.close()
    }
  }

  /**
   * Compute the schema string for this RDD.
   */
  def schemaString(schema: StructType, dialect: JdbcDialect): String = {
    val sb = new StringBuilder()
    schema.fields.foreach { field =>
      val dataType = field.dataType
      val typeString: String =
        dialect.getJDBCType(dataType, field.metadata).map(_.databaseTypeDefinition).getOrElse(
          dataType match {
            case IntegerType => "INTEGER"
            case LongType => "BIGINT"
            case DoubleType => "DOUBLE PRECISION"
            case FloatType => "REAL"
            case ShortType => "INTEGER"
            case ByteType => "BYTE"
            case BooleanType => "BIT(1)"
            case StringType => "TEXT"
            case BinaryType => "BLOB"
            case TimestampType => "TIMESTAMP"
            case DateType => "DATE"
            case DecimalType.Fixed(precision, scale) =>
              s"DECIMAL($precision,$scale)"
            case _ => throw new IllegalArgumentException(
              s"Don't know how to save $field to JDBC")
          })
      val nullable = if (field.nullable) "" else "NOT NULL"
      sb.append(s", ${field.name} $typeString $nullable")
    }
    if (sb.length < 2) "" else "(".concat(sb.substring(2)).concat(")")
  }

  def tableExistsInMetaData(table: String, conn: Connection,
      dialect: JdbcDialect): Boolean = {
    // using the JDBC meta-data API
    val dotIndex = table.indexOf('.')
    val schemaName = if (dotIndex > 0) {
      table.substring(0, dotIndex)
    } else {
      // get the current schema
      getCurrentSchema(conn, dialect)
    }
    val tableName = if (dotIndex > 0) table.substring(dotIndex + 1) else table
    try {
      val rs = conn.getMetaData.getTables(null, schemaName, tableName, null)
      rs.next()
    } catch {
      case t: java.sql.SQLException => false
    }
  }

  def getCurrentSchema(conn: Connection,
      dialect: JdbcDialect): String = {
    dialect match {
      case d: JdbcExtendedDialect => d.getCurrentSchema(conn)
      case _ => conn.getSchema
    }
  }

  def createSchema(schemaName: String, conn: Connection,
      dialect: JdbcDialect): Unit = {
    dialect match {
      case d: JdbcExtendedDialect => d.createSchema(schemaName, conn)
      case _ => //ignore
    }
  }

  /**
   * Returns true if the table already exists in the JDBC database.
   */
  def tableExists(table: String, conn: Connection, dialect: JdbcDialect,
      context: SQLContext): Boolean = {
    dialect match {
      case d: JdbcExtendedDialect => d.tableExists(table, conn, context)

      case _ =>
        try {
          tableExistsInMetaData(table, conn, dialect)
        } catch {
          case NonFatal(_) =>
            val stmt = conn.createStatement()
            // try LIMIT clause, then FETCH FIRST and lastly COUNT
            val testQueries = Array(s"SELECT 1 FROM $table LIMIT 1",
              s"SELECT 1 FROM $table FETCH FIRST ROW ONLY",
              s"SELECT COUNT(1) FROM $table")
            for (q <- testQueries) {
              try {
                val rs = stmt.executeQuery(q)
                rs.next()
                rs.close()
                stmt.close()
                // return is not very efficient but then this code
                // is not performance sensitive
                return true
              } catch {
                case NonFatal(_) => // continue
              }
            }
            false
        }
    }
  }

  def dropTable(conn: Connection, tableName: String, dialect: JdbcDialect,
      context: SQLContext, ifExists: Boolean): Unit = {
    dialect match {
      case d: JdbcExtendedDialect =>
        d.dropTable(tableName, conn, context, ifExists)
      case _ =>
        if (!ifExists || tableExists(tableName, conn, dialect, context)) {
          JdbcExtendedUtils.executeUpdate(s"DROP TABLE $tableName", conn)
        }
    }
  }

  def truncateTable(conn: Connection, tableName: String, dialect: JdbcDialect): Unit = {
    dialect match {
      case d: JdbcExtendedDialect =>
        JdbcExtendedUtils.executeUpdate(d.truncateTable(tableName), conn)
      case _ =>
        JdbcExtendedUtils.executeUpdate(s"TRUNCATE TABLE $tableName", conn)
    }
  }

  /**
   * Create a [[ResolvedDataSource]] for an external DataSource schema DDL
   * string specification.
   */
  def externalResolvedDataSource(
      sqlContext: SQLContext,
      schemaString: String,
      provider: String,
      mode: SaveMode,
      options: Map[String, String]): ResolvedDataSource = {
    val clazz: Class[_] = ResolvedDataSource.lookupDataSource(provider)
    val relation = clazz.newInstance() match {

      case dataSource: ExternalSchemaRelationProvider =>
        // add schemaString as separate property for Hive persistence
        dataSource.createRelation(sqlContext, mode, new CaseInsensitiveMap(
          options + (SCHEMA_PROPERTY -> schemaString)), schemaString)

      case _ => throw new AnalysisException(
        s"${clazz.getCanonicalName} is not an ExternalSchemaRelationProvider.")
    }
    new ResolvedDataSource(clazz, relation)
  }
}
