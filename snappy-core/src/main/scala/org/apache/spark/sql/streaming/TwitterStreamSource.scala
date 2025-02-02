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

import twitter4j.auth.{Authorization, OAuthAuthorization}
import twitter4j.conf.{Configuration, ConfigurationBuilder}

import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources.BaseRelation
import org.apache.spark.sql.types.StructType
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.twitter.TwitterUtils

final class TwitterStreamSource extends StreamPlanProvider {

  override def createRelation(sqlContext: SQLContext,
      options: Map[String, String],
      schema: StructType): BaseRelation = {
    new TwitterStreamRelation(sqlContext, options, schema)
  }
}

final class TwitterStreamRelation(
    @transient override val sqlContext: SQLContext,
    options: Map[String, String],
    override val schema: StructType)
    extends StreamBaseRelation(options) {

  val consumerKey = options("consumerKey")
  val consumerSecret = options("consumerSecret")
  val accessToken = options("accessToken")
  val accessTokenSecret = options("accessTokenSecret")

  //  TODO Yogesh, need to pass this through DDL
  val filters = Seq("e")

  private val getTwitterConf: Configuration = {
    val twitterConf = new ConfigurationBuilder()
        .setOAuthConsumerKey(consumerKey)
        .setOAuthConsumerSecret(consumerSecret)
        .setOAuthAccessToken(accessToken)
        .setOAuthAccessTokenSecret(accessTokenSecret)
        .setJSONStoreEnabled(true)
        .build()
    twitterConf
  }

  private def createOAuthAuthorization(): Authorization = {
    new OAuthAuthorization(getTwitterConf)
  }

  override protected def createRowStream(): DStream[InternalRow] =
    TwitterUtils.createStream(context, Some(createOAuthAuthorization()),
      filters, storageLevel).flatMap(rowConverter.toRows)
}
