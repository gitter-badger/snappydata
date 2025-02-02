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
package io.snappydata.impl

import java.sql.SQLException
import java.util.Properties

import com.pivotal.gemfirexd.internal.engine.fabricservice.{FabricLocatorImpl}
import io.snappydata.{ProtocolOverrides, Locator}

class LocatorImpl extends FabricLocatorImpl with Locator with ProtocolOverrides {

  @throws(classOf[SQLException])
  override def start(bindAddress: String, port: Int,
                     bootProperties: Properties) = {
    start(bindAddress, port, bootProperties, false)
  }

  @throws(classOf[SQLException])
  override def start(bindAddress: String, port: Int,
                     bootProperties: Properties, ignoreIfStarted: Boolean) = {
    synchronized {
      super.start(bindAddress, port, bootProperties, ignoreIfStarted)
    }
  }

}