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
package org.apache.spark.scheduler.cluster

import io.snappydata.impl.LeadImpl
import io.snappydata.{Constant, Property, Utils}
import org.slf4j.LoggerFactory

import org.apache.spark.SparkContext
import org.apache.spark.scheduler._

/**
  * Snappy's cluster manager that is responsible for creating
  * scheduler and scheduler backend.
  */
class SnappyEmbeddedModeClusterManager extends ExternalClusterManager {

  val logger = LoggerFactory.getLogger(getClass)

  SnappyClusterManager.init(this)

  var schedulerBackend: SnappyCoarseGrainedSchedulerBackend = null

  def createTaskScheduler(sc: SparkContext): TaskScheduler = {
    // If there is an application that is trying to join snappy
    // as lead in embedded mode, we need the locator to connect
    // to the snappy distributed system and hence the locator is
    // passed in masterurl itself.
    if (sc.master.startsWith(Constant.SNAPPY_URL_PREFIX)) {
      val locator = sc.master.replaceFirst(Constant.SNAPPY_URL_PREFIX, "").trim

      val (prop, value) = {
        if (locator.indexOf("mcast-port") >= 0) {
          val split = locator.split("=")
          (split(0).trim, split(1).trim)
        }
        else if (locator.isEmpty ||
            locator == "" ||
            locator == "null" ||
            !Utils.LocatorURLPattern.matcher(locator).matches()
        ) {
          throw new Exception(s"locator info not provided in the snappy embedded url ${sc.master}")
        }
        (Property.locators, locator)
      }

      logger.info(s"setting from url $prop with $value")
      sc.conf.set(prop, value)
      sc.conf.set(Property.embedded, "true")
    }
    new SnappyTaskSchedulerImpl(sc)
  }

  def canCreate(masterURL: String): Boolean =
    masterURL.startsWith("snappydata")

  def createSchedulerBackend(sc: SparkContext,
      scheduler: TaskScheduler): SchedulerBackend = {
    schedulerBackend = new SnappyCoarseGrainedSchedulerBackend(
        scheduler.asInstanceOf[TaskSchedulerImpl], sc.env.rpcEnv)

    schedulerBackend
  }

  def initialize(scheduler: TaskScheduler,
      backend: SchedulerBackend): Unit = {
    assert(scheduler.isInstanceOf[TaskSchedulerImpl])
    val schedulerImpl = scheduler.asInstanceOf[TaskSchedulerImpl]

    schedulerImpl.initialize(backend)

    LeadImpl.invokeLeadStart(schedulerImpl.sc)
  }

  def stopLead(): Unit = {
    LeadImpl.invokeLeadStop(null)
  }

}

object SnappyClusterManager {

  private[this] var _cm : SnappyEmbeddedModeClusterManager = null

  def init(mgr: SnappyEmbeddedModeClusterManager): Unit = {
    _cm = mgr
  }

  def cm : Option[SnappyEmbeddedModeClusterManager] = if (_cm != null) Some(_cm) else None
}
