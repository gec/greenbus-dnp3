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

import java.io.FileInputStream
import java.util.UUID

import akka.actor.{ ActorContext, ActorSystem }
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.app.actor.frontend.json.{ JsonFrontendRegistrationConfig, JsonFrontendConfiguration }
import io.greenbus.app.actor.json.JsonAmqpConfig
import io.greenbus.app.actor.{ AmqpConnectionConfig, EndpointCollectionStrategy, ProtocolsEndpointStrategy }
import io.greenbus.app.actor.frontend._
import io.greenbus.dnp3.master.{ IndexMapping, OutputMapping }
import org.apache.commons.io.IOUtils

object Dnp3MasterFrontend extends Logging {

  def main(args: Array[String]) {
    System.loadLibrary("opendnp3")
    System.setProperty("reef.api.protocol.dnp3.nostaticload", "")

    val baseDir = Option(System.getProperty("io.greenbus.config.base")).getOrElse("")

    val jsonConfigPath = Option(System.getProperty("io.greenbus.config.dnp3")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.frontend.dnp3.json")

    val amqpConfigPath = Option(System.getProperty("io.greenbus.config.amqp")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.msg.amqp.cfg")
    val userConfigPath = Option(System.getProperty("io.greenbus.config.user")).map(baseDir + _).getOrElse(baseDir + "io.greenbus.user.cfg")

    val protocols = Set("dnp3")

    val processConfig = loadConfig(jsonConfigPath, amqpConfigPath, userConfigPath, protocols)

    def protocolMgrFactory(context: ActorContext): MasterProtocol[(Dnp3MasterConfig, IndexMapping, Map[String, OutputMapping])] = new Dnp3MasterProtocol

    if (Option(System.getProperty("akka.logger-startup-timeout")).isEmpty) {
      System.setProperty("akka.logger-startup-timeout", "30s")
    }

    val rootConfig = ConfigFactory.load()
    val slf4jConfig = ConfigFactory.parseString("""akka { loggers = ["akka.event.slf4j.Slf4jLogger"] }""")
    val akkaConfig = slf4jConfig.withFallback(rootConfig)
    val system = ActorSystem("dnp3", akkaConfig)

    val mgr = system.actorOf(FrontendFactory.create(
      processConfig.amqpConfig,
      processConfig.userConfigPath,
      processConfig.collectionStrategy,
      protocolMgrFactory,
      Dnp3MasterProtocol,
      Seq(Dnp3MasterProtocol.configKey),
      nodeId = processConfig.nodeId,
      connectionRepresentsLock = true,
      registrationConfig = processConfig.registrationConfig))
  }

  def loadConfig(jsonPath: String, defaultAmqpConfigPath: String, defaultUserConfigPath: String, protocols: Set[String]): FrontendProcessConfig = {

    loadJsonConfig(jsonPath) match {
      case Some(jsonConfig) => {

        val amqpConfig = jsonConfig.amqpConfig
          .map(JsonAmqpConfig.read(_, Seq(defaultAmqpConfigPath)))
          .getOrElse(AmqpConnectionConfig.default(defaultAmqpConfigPath))

        val userConfigPath = jsonConfig.userConfigPath.getOrElse(defaultUserConfigPath)

        val nodeId = jsonConfig.nodeId.getOrElse(UUID.randomUUID().toString)

        val collStrat = jsonConfig.endpointWhitelist match {
          case None => new ProtocolsEndpointStrategy(protocols)
          case Some(list) => new ProtocolsEndpointStrategy(protocols, Some(list.toSet))
        }

        val regConfig = jsonConfig.registrationConfig.map(JsonFrontendRegistrationConfig.read).getOrElse(FrontendRegistrationConfig.defaults)

        FrontendProcessConfig(amqpConfig, userConfigPath, collStrat, nodeId, regConfig)

      }
      case None => {

        FrontendProcessConfig(
          AmqpConnectionConfig.default(defaultAmqpConfigPath),
          defaultUserConfigPath,
          new ProtocolsEndpointStrategy(protocols),
          UUID.randomUUID().toString,
          FrontendRegistrationConfig.defaults)
      }
    }

  }

  def loadJsonConfig(path: String): Option[JsonFrontendConfiguration] = {

    try {
      val bytes = IOUtils.toByteArray(new FileInputStream(path))
      JsonFrontendConfiguration.load(bytes)
    } catch {
      case ex: Throwable =>
        logger.error("Could not load json configuration: " + path + ", reason: " + ex.getMessage)
        None
    }

  }

  def loadEndpointCollectionStrategy(protocols: Set[String], baseDir: String, property: String): EndpointCollectionStrategy = {

    val endpointCollectionPathOpt = Option(System.getProperty(property))

    endpointCollectionPathOpt match {
      case None => new ProtocolsEndpointStrategy(protocols)
      case Some(endpointCollectionPath) =>
        val bytes = IOUtils.toByteArray(new FileInputStream(endpointCollectionPath))
        val jsonConfig = JsonFrontendConfiguration.load(bytes).getOrElse {
          throw new IllegalArgumentException(s"Could not parse endpoint collection configuration file $endpointCollectionPath")
        }

        new ProtocolsEndpointStrategy(protocols, jsonConfig.endpointWhitelist.map(_.toSet))
    }
  }
}
