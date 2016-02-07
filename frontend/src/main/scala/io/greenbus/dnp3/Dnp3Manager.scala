/**
 * Copyright 2011-16 Green Energy Corp.
 *
 * Licensed to Green Energy Corp (www.greenenergycorp.com) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. Green Energy
 * Corp licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.greenbus.dnp3

import org.totalgrid.dnp3._
import io.greenbus.client.service.proto.Model.ModelUUID
import io.greenbus.dnp3.common.{ LogAdapter, StackAdapter, StackObserver }
import io.greenbus.dnp3.master.{ IndexMapping, _ }

case class Dnp3MasterConfig(stack: MasterStackConfig, logLevel: FilterLevel, address: String, port: Int, retryMs: Long)

class Dnp3Manager {
  private val stackManager = new StackManager
  private val logAdapter = new LogAdapter

  stackManager.AddLogHook(logAdapter)

  private var observerMap = Map.empty[ModelUUID, (String, String, MeasAdapter, StackAdapter)]

  def addMaster(uuid: ModelUUID, name: String, config: Dnp3MasterConfig, mapping: IndexMapping, controls: Map[String, OutputMapping], measObserver: MeasurementObserver, stackObserver: StackObserver): CommandAdapter = {

    removeMaster(uuid)

    val portName = s"$name-${config.address}:${config.port}"

    val settings = new PhysLayerSettings(config.logLevel, config.retryMs)

    stackManager.AddTCPClient(portName, settings, config.address, config.port)

    val measAdapter = new MeasAdapter(mapping, measObserver.accept)

    val stackAdapter = new StackAdapter(stackObserver)

    config.stack.getMaster.setMpObserver(stackAdapter)

    val commandAcceptor = stackManager.AddMaster(portName, name, config.logLevel, measAdapter, config.stack)

    observerMap += ((uuid, (name, portName, measAdapter, stackAdapter)))

    new CommandAdapter(commandAcceptor, controls)
  }

  def removeMaster(uuid: ModelUUID) {
    observerMap.get(uuid).foreach {
      case (name, portName, _, _) =>
        stackManager.RemoveStack(name)
        stackManager.RemovePort(portName)
        observerMap -= uuid
    }
  }

  def shutdown() {
    stackManager.Shutdown()
  }
}
