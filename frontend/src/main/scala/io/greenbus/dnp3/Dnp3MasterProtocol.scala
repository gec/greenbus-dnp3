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

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.app.actor.frontend._
import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus
import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.client.service.proto.Model.{ Endpoint, EntityKeyValue, ModelUUID }
import io.greenbus.dnp3.common.StackObserver
import io.greenbus.dnp3.master.{ Dnp3XmlConfigurer, IndexMapping, MeasurementObserver, OutputMapping }

object Dnp3MasterProtocol extends ProtocolConfigurer[(Dnp3MasterConfig, IndexMapping, Map[String, OutputMapping])] with LazyLogging {

  val configKey = "protocolConfig"

  private val marshaller = new Dnp3XmlConfigurer

  private def extractConfig(config: EntityKeyValue): Option[(Dnp3MasterConfig, IndexMapping, Map[String, OutputMapping])] = {
    if (config.getValue.hasByteArrayValue) {
      try {
        Some(marshaller.readMasterConfig(config.getValue.getByteArrayValue.toByteArray))
      } catch {
        case ex: Throwable =>
          logger.warn("Couldn't unmarshal dnp3 configuration: " + ex)
          None
      }
    } else {
      None
    }
  }

  def evaluate(endpoint: Endpoint, configFiles: Seq[EntityKeyValue]): Option[(Dnp3MasterConfig, IndexMapping, Map[String, OutputMapping])] = {
    configFiles.find(_.getKey == configKey).flatMap(extractConfig)
  }

  def equivalent(latest: (Dnp3MasterConfig, IndexMapping, Map[String, OutputMapping]), previous: (Dnp3MasterConfig, IndexMapping, Map[String, OutputMapping])): Boolean = {
    false
  }
}

class Dnp3MasterProtocol extends MasterProtocol[(Dnp3MasterConfig, IndexMapping, Map[String, OutputMapping])] with LazyLogging {

  private val mgr = new Dnp3Manager

  def add(endpoint: Endpoint, protocolConfig: (Dnp3MasterConfig, IndexMapping, Map[String, OutputMapping]), publish: (MeasurementsPublished) => Unit, statusUpdate: (StackStatusUpdated) => Unit): ProtocolCommandAcceptor = {

    mgr.synchronized {

      val measObs = new MeasurementObserver {
        def accept(wallTime: Long, measurements: Seq[(String, Measurement)]) {
          publish(MeasurementsPublished(wallTime, Seq(), measurements))
        }
      }

      val stackObs = new StackObserver {
        def update(state: FrontEndConnectionStatus.Status) {
          statusUpdate(StackStatusUpdated(state))
        }
      }

      val (masterCfg, indexMapping, outputMapping) = protocolConfig

      mgr.addMaster(endpoint.getUuid, endpoint.getName, masterCfg, indexMapping, outputMapping, measObs, stackObs)

    }
  }

  def remove(endpointUuid: ModelUUID) {
    mgr.synchronized {
      mgr.removeMaster(endpointUuid)
    }
  }

  def shutdown() {
    mgr.synchronized {
      mgr.shutdown()
    }
  }
}
