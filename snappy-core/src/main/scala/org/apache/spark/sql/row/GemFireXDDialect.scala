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
package org.apache.spark.sql.row

import java.sql.{Connection, Types}
import java.util.Properties

import io.snappydata.Constant

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.collection.Utils._
import org.apache.spark.sql.jdbc.{JdbcDialects, JdbcType}
import org.apache.spark.sql.sources.{JdbcExtendedDialect, JdbcExtendedUtils}
import org.apache.spark.sql.types._

/**
 * Default dialect for GemFireXD >= 1.4.0.
 * Contains specific type conversions to and from Spark SQL catalyst types.
 */
@DeveloperApi
case object GemFireXDDialect extends GemFireXDBaseDialect {

  // register the dialect
  JdbcDialects.registerDialect(GemFireXDDialect)

  def canHandle(url: String): Boolean =
      (url.startsWith("jdbc:gemfirexd:") ||
      url.startsWith("jdbc:snappydata:")) &&
      !url.startsWith("jdbc:gemfirexd://") &&
      !url.startsWith("jdbc:snappydata://")

  override def extraDriverProperties(isLoner: Boolean): Properties = {
    isLoner match {
      case true => new Properties
      case false =>
        val props = new Properties()
        props.setProperty("host-data", "false")
        props
    }
  }

  override def getPartitionByClause(col : String): String = s"partition by column($col)"
}

/**
 * Default dialect for GemFireXD >= 1.4.0.
 * Contains specific type conversions to and from Spark SQL catalyst types.
 */
@DeveloperApi
case object GemFireXDClientDialect extends GemFireXDBaseDialect {

  // register the dialect
  JdbcDialects.registerDialect(GemFireXDClientDialect)

  def canHandle(url: String): Boolean =
      url.startsWith("jdbc:gemfirexd://") ||
      url.startsWith("jdbc:snappydata://")

  override def getPartitionByClause(col : String): String = s"partition by column($col)"
}

abstract class GemFireXDBaseDialect extends JdbcExtendedDialect {

  def init(): Unit = {
    // do nothing; just forces one-time invocation of various registerDialects
    GemFireXDDialect.getClass
    GemFireXDClientDialect.getClass
  }

  protected val bitTypeName = "bit".normalize
  protected val floatTypeName = "float".normalize
  protected val realTypeName = "real".normalize
  protected val varcharTypeName = "varchar".normalize

  override def getCatalystType(sqlType: Int, typeName: String,
                      size: Int, md: MetadataBuilder): Option[DataType] = {
    if (sqlType == Types.FLOAT && typeName.normalize.equals(floatTypeName)) {
      Some(DoubleType)
    } else if (sqlType == Types.REAL &&
      typeName.normalize.equals(realTypeName)) {
      Some(FloatType)
    } else if (sqlType == Types.BIT && size > 1 &&
      typeName.normalize.equals(bitTypeName)) {
      Some(BinaryType)
    } else if (sqlType == Types.VARCHAR && size > 1 &&
        typeName.normalize.equals(varcharTypeName)) {
      md.putLong("maxlength", size)
      Some(StringType)
    } else None
  }

  override def getJDBCType(dt: DataType): Option[JdbcType] = dt match {
    case StringType => Some(JdbcType("CLOB", java.sql.Types.CLOB))
    case BinaryType => Some(JdbcType("BLOB", java.sql.Types.BLOB))
    case BooleanType => Some(JdbcType("BOOLEAN", java.sql.Types.BOOLEAN))
    // TODO: check if this should be INTEGER for GemFireXD for below two
    case ByteType => Some(JdbcType("SMALLINT", java.sql.Types.INTEGER))
    case ShortType => Some(JdbcType("SMALLINT", java.sql.Types.INTEGER))
    case DecimalType.Fixed(precision, scale) =>
      Some(JdbcType(s"DECIMAL($precision,$scale)", java.sql.Types.DECIMAL))
    case _ => None
  }

  /**
   * Look SPARK-10101 issue for similar problem. If the PR raised is
   * ever merged we can remove this method here.
   */
  override def getJDBCType(dt: DataType, md: Metadata): Option[JdbcType] = dt match {
    case StringType =>
      if (md.contains("maxlength")) {
        Some(JdbcType(s"VARCHAR(${md.getLong("maxlength")})",
          java.sql.Types.VARCHAR))
      } else {
        Some(JdbcType("CLOB", java.sql.Types.CLOB))
      }
    case _ => getJDBCType(dt)
  }

  override def createSchema(schemaName: String, conn: Connection): Unit = {
    JdbcExtendedUtils.executeUpdate("CREATE SCHEMA " + schemaName, conn)
  }

  override def dropTable(tableName: String, conn: Connection,
      context: SQLContext, ifExists: Boolean): Unit = {
    if (ifExists) {
      JdbcExtendedUtils.executeUpdate(s"DROP TABLE IF EXISTS $tableName", conn)
    } else {
      JdbcExtendedUtils.executeUpdate(s"DROP TABLE $tableName", conn)
    }
  }

  override def initializeTable(tableName: String, caseSensitive: Boolean,
      conn: Connection): Unit = {
    val dotIndex = tableName.indexOf('.')
    val (schema, table) = if(dotIndex > 0){
      (tableName.substring(0, dotIndex), tableName.substring(dotIndex + 1))
    } else {
      (Constant.DEFAULT_SCHEMA, tableName)
    }
    val stmt = conn.createStatement()
    val rs = stmt.executeQuery("select datapolicy from sys.systables where " +
        s"tableName='$table' and tableschemaname='$schema'")
    val result = if (rs.next()) rs.getString(1) else null
    rs.close()
    stmt.close()
    if ("PARTITION".equalsIgnoreCase(result) ||
        "PERSISTENT_PARTITION".equalsIgnoreCase(result)) {

      JdbcExtendedUtils.executeUpdate(
        s"call sys.CREATE_ALL_BUCKETS('$tableName')", conn)
    }
  }
}
