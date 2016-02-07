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
package io.greenbus.dnp3.integration

import java.util.UUID

import akka.actor.{ ActorContext, ActorRef, ActorSystem }
import com.typesafe.scalalogging.slf4j.Logging
import org.scalatest.matchers.ShouldMatchers
import org.totalgrid.dnp3.{ IDataObserver, StackManager, StackStates }
import io.greenbus.msg.Session
import io.greenbus.msg.amqp.AmqpSettings
import io.greenbus.msg.qpid.QpidBroker
import io.greenbus.app.actor.{ AmqpConnectionConfig, ProtocolsEndpointStrategy }
import io.greenbus.app.actor.frontend.{ FrontendRegistrationConfig, FrontendFactory, MasterProtocol }
import io.greenbus.client.ServiceConnection
import io.greenbus.client.service.proto.Measurements.MeasurementNotification
import io.greenbus.dnp3.integration.Dnp3IntegrationConfiguration._
import io.greenbus.dnp3.integration.tools.SlaveObserver
import io.greenbus.dnp3.master.{ IndexMapping, OutputMapping }
import io.greenbus.dnp3.{ Dnp3MasterConfig, Dnp3MasterProtocol }
import io.greenbus.integration.tools.ServiceWatcher
import io.greenbus.measproc.MeasurementProcessor
import io.greenbus.services.{ CoreServices, ResetDatabase, ServiceManager }
import io.greenbus.util.UserSettings

import scala.concurrent.Await
import scala.concurrent.duration._

class Dnp3Fixture extends ShouldMatchers with Logging {
  import io.greenbus.integration.tools.PollingUtils._

  val testConfigPath = "io.greenbus.test.cfg"
  val system = ActorSystem("integrationTest")

  var services = Option.empty[ActorRef]
  var processor = Option.empty[ActorRef]
  var conn = Option.empty[ServiceConnection]
  var session = Option.empty[Session]

  var dnpMgr = Option.empty[ActorRef]
  var dnpEndpointMgr = Option.empty[ActorRef]

  val stackManager = new StackManager
  val slaveObserver = new SlaveObserver
  var slaveDataOpt = Option.empty[IDataObserver]

  var measWatcher = Option.empty[ServiceWatcher[MeasurementNotification]]

  def init() {
    val dataObserver = loadSlave("01", 33004, stackManager, slaveObserver.commandAcceptor, Some(slaveObserver.stackObserver))
    this.slaveDataOpt = Some(dataObserver)

    ResetDatabase.reset(testConfigPath)

    logger.info("starting services")
    services = Some(system.actorOf(ServiceManager.props(testConfigPath, testConfigPath, CoreServices.runServices)))

    val amqpConfig = AmqpSettings.load(testConfigPath)
    val conn = ServiceConnection.connect(amqpConfig, QpidBroker, 2000)
    this.conn = Some(conn)

    val userConfig = UserSettings.load(testConfigPath)

    val session = pollForSuccess(500, 5000) {
      Await.result(conn.login(userConfig.user, userConfig.password), 500.milliseconds)
    }

    this.session = Some(session)

    Dnp3IntegrationConfiguration.loadActions(Dnp3IntegrationConfiguration.buildConfig("Device01", "Endpoint01", 33004, "dnp3"), session)

    logger.info("starting processor")
    processor = Some(system.actorOf(MeasurementProcessor.buildProcessor(testConfigPath, testConfigPath, testConfigPath, 20000, "testNode")))

    Thread.sleep(300)

    val (originals, measWatcher) = ServiceWatcher.measurementWatcherForOwner(session, "Device01")
    this.measWatcher = Some(measWatcher)

    val stackUpFut = slaveObserver.stackWatcher.future(_.length > 1)

    def protocolMgrFactory(context: ActorContext): MasterProtocol[(Dnp3MasterConfig, IndexMapping, Map[String, OutputMapping])] = new Dnp3MasterProtocol

    val endpointStrategy = new ProtocolsEndpointStrategy(Set("dnp3"))

    val amqpConfigFull = AmqpConnectionConfig.default(testConfigPath)

    val regConfig = FrontendRegistrationConfig(
      loginRetryMs = 1000,
      registrationRetryMs = 1000,
      releaseTimeoutMs = 20000,
      statusHeartbeatPeriodMs = 5000,
      lapsedTimeMs = 11000,
      statusRetryPeriodMs = 2000,
      measRetryPeriodMs = 2000,
      measQueueLimit = 1000,
      configRequestRetryMs = 5000)

    val endpointMgr = system.actorOf(FrontendFactory.create(
      amqpConfigFull, testConfigPath, endpointStrategy, protocolMgrFactory, Dnp3MasterProtocol, Seq(Dnp3MasterProtocol.configKey),
      connectionRepresentsLock = true,
      nodeId = UUID.randomUUID().toString,
      regConfig))

    this.dnpEndpointMgr = Some(endpointMgr)

    val stateChange = Await.result(stackUpFut, 10000.milliseconds)

    stateChange should equal(List(StackStates.SS_COMMS_DOWN, StackStates.SS_COMMS_UP))
  }

  def shutdown() {
    system.shutdown()
    measWatcher.foreach(_.cancel())
    conn.foreach(_.disconnect())
    stackManager.Shutdown()

  }
}