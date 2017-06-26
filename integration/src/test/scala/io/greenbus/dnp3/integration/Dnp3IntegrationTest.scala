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
import akka.pattern.ask
import com.google.protobuf.ByteString
import com.typesafe.scalalogging.LazyLogging
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{ BeforeAndAfterAll, FunSuite, Matchers }
import org.totalgrid.dnp3.{ Transaction, _ }
import io.greenbus.msg.Session
import io.greenbus.msg.amqp.AmqpSettings
import io.greenbus.msg.qpid.QpidBroker
import io.greenbus.app.actor.{ AmqpConnectionConfig, ProtocolsEndpointStrategy }
import io.greenbus.app.actor.frontend.{ FrontEndSetManager, FrontendFactory, FrontendRegistrationConfig, MasterProtocol }
import io.greenbus.client.ServiceConnection
import io.greenbus.client.service.proto.CommandRequests.CommandSelect
import io.greenbus.client.service.proto.Commands
import io.greenbus.client.service.proto.FrontEnd.{ FrontEndConnectionStatus, FrontEndConnectionStatusNotification }
import io.greenbus.client.service.proto.Measurements.{ MeasurementNotification, Quality }
import io.greenbus.client.service.proto.Model.{ EntityKeyValue, StoredValue }
import io.greenbus.client.service.proto.ModelRequests.EntityKeySet
import io.greenbus.client.service.{ CommandService, ModelService }
import io.greenbus.dnp3.integration.tools.SlaveObserver
import io.greenbus.dnp3.master.{ IndexMapping, OutputMapping }
import io.greenbus.dnp3.{ Dnp3MasterConfig, Dnp3MasterProtocol }
import io.greenbus.integration.tools.ServiceWatcher
import io.greenbus.measproc.MeasurementProcessor
import io.greenbus.services.{ CoreServices, ResetDatabase, ServiceManager }
import io.greenbus.util.UserSettings

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class Dnp3IntegrationTest extends FunSuite with Matchers with LazyLogging with BeforeAndAfterAll {
  import io.greenbus.dnp3.integration.Dnp3IntegrationConfiguration._
  import io.greenbus.dnp3.integration.tools.SlaveObserver._
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
  var statusWatcher = Option.empty[ServiceWatcher[FrontEndConnectionStatusNotification]]

  val slaveObserver2 = new SlaveObserver
  var slaveDataOpt2 = Option.empty[IDataObserver]

  // start, stack goes up
  // meas update
  // command issue
  // meas update during gb down
  // config update: start second slave, swap address of dnp3 slave with config
  // stop gb connection, kill config, bring it up, see slave disconnect
  // stop gb connection, kill endpoint, bring it up, see slave disconnect

  // ADVANCED:
  // add/remove points
  // add/remove commands
  // one parse fails, everything else cool
  // could switch index mapping without resetting connection

  override protected def beforeAll(): Unit = {

    val dataObserver = loadSlave("01", 33004, stackManager, slaveObserver.commandAcceptor, Some(slaveObserver.stackObserver))
    this.slaveDataOpt = Some(dataObserver)

    val dataObserver2 = loadSlave("02", 33005, stackManager, slaveObserver2.commandAcceptor, Some(slaveObserver2.stackObserver))
    this.slaveDataOpt2 = Some(dataObserver2)

    ResetDatabase.reset(testConfigPath)

    logger.info("starting services")
    services = Some(system.actorOf(ServiceManager.props(testConfigPath, testConfigPath, CoreServices.runServicesSql)))

    val amqpConfig = AmqpSettings.load(testConfigPath)
    val conn = ServiceConnection.connect(amqpConfig, QpidBroker, 5000)
    this.conn = Some(conn)

    val userConfig = UserSettings.load(testConfigPath)

    val session = pollForSuccess(500, 5000) {
      Await.result(conn.login(userConfig.user, userConfig.password), 500.milliseconds)
    }

    this.session = Some(session)

    Dnp3IntegrationConfiguration.loadActions(Dnp3IntegrationConfiguration.buildConfig("Device01", "Endpoint01", 33004, "dnp3"), session)

    logger.info("starting processor")
    processor = Some(system.actorOf(MeasurementProcessor.buildProcessor(testConfigPath, testConfigPath, testConfigPath, testConfigPath, 20000, "testNode")))
    Thread.sleep(500)
  }

  override protected def afterAll(): Unit = {
    stackManager.Shutdown()
    this.measWatcher.foreach(_.cancel())
    this.conn.foreach(_.disconnect())
  }

  test("Initialized") {
    val session = this.session.get

    val (originals, measWatcher) = ServiceWatcher.measurementWatcherForOwner(session, "Device01")
    this.measWatcher = Some(measWatcher)

    val (originalStatuses, statusWatcher) = ServiceWatcher.statusWatcher(session, "Endpoint01")
    this.statusWatcher = Some(statusWatcher)

    val allUpdated = measWatcher.watcher.future { notes =>
      notes.map(_.getPointName).toSet == allPoints
    }

    val statusUpdated = statusWatcher.watcher.future { notes =>
      notes.exists(_.getValue.getState == FrontEndConnectionStatus.Status.COMMS_UP)
    }

    val stackUpFut = slaveObserver.stackWatcher.future(_.length > 1)

    def protocolMgrFactory(context: ActorContext): MasterProtocol[(Dnp3MasterConfig, IndexMapping, Map[String, OutputMapping])] = new Dnp3MasterProtocol

    val endpointStrategy = new ProtocolsEndpointStrategy(Set("dnp3"))

    val amqpConfigFull = AmqpConnectionConfig.default(testConfigPath)

    val regConfig = FrontendRegistrationConfig(
      loginRetryMs = 1000,
      registrationRetryMs = 5000,
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

    val stateChange = Await.result(stackUpFut, 5000.milliseconds)

    stateChange should equal(List(StackStates.SS_COMMS_DOWN, StackStates.SS_COMMS_UP))

    val initialUpdate = Await.result(allUpdated, 5000.milliseconds)

    val statusResults = Await.result(statusUpdated, 5000.milliseconds)

    initialUpdate.foreach { update =>
      update.getValue.hasQuality should equal(true)
      update.getValue.getQuality.hasValidity should equal(true)
      update.getValue.getQuality.getValidity should equal(Quality.Validity.INVALID)
      update.getValue.getQuality.hasDetailQual should equal(true)
      update.getValue.getQuality.getDetailQual.hasOldData should equal(true)
      update.getValue.getQuality.getDetailQual.getOldData should equal(true)
    }

    initialUpdate.filter(up => statusPoints.contains(up.getPointName)).foreach { update =>
      update.getValue.hasBoolVal should equal(true)
      update.getValue.getBoolVal should equal(false)
    }

    initialUpdate.filter(up => analogPoints.contains(up.getPointName)).foreach { update =>
      update.getValue.hasDoubleVal should equal(true)
      update.getValue.getDoubleVal should equal(0.0)
    }
  }

  def observerTransaction(dataObserver: IDataObserver)(f: IDataObserver => Unit) {
    val transaction = new Transaction
    transaction.Start(dataObserver)
    try {
      f(dataObserver)
    } finally {
      transaction.End()
    }
  }

  def checkStatus(update: MeasurementNotification, name: String, v: Boolean) {
    update.getPointName should equal(name)
    update.getValue.hasBoolVal should equal(true)
    update.getValue.getBoolVal should equal(v)

    update.getValue.hasQuality should equal(true)
    update.getValue.getQuality.getValidity should equal(Quality.Validity.GOOD)
  }

  def checkAnalog(update: MeasurementNotification, name: String, v: Double) {
    update.getPointName should equal(name)
    update.getValue.hasDoubleVal should equal(true)
    update.getValue.getDoubleVal should equal(v)

    update.getValue.hasQuality should equal(true)
    update.getValue.getQuality.getValidity should equal(Quality.Validity.GOOD)
  }

  test("Measurement update") {
    val session = this.session.get
    val dataObserver = this.slaveDataOpt.get
    val measWatcher = this.measWatcher.get
    measWatcher.watcher.reset()

    val measUpdated = measWatcher.watcher.future(_.nonEmpty)

    observerTransaction(dataObserver) { obs =>
      obs.Update(new Binary(true, BinaryQuality.BQ_ONLINE.swigValue().toShort), 1)
    }

    val updates = Await.result(measUpdated, 5000.milliseconds)

    updates.size should equal(1)
    val update = updates.head
    checkStatus(update, "Device01.Status02", true)

    measWatcher.watcher.reset()

    val analogUpdated = measWatcher.watcher.future(_.nonEmpty)

    observerTransaction(dataObserver) { obs =>
      obs.Update(new Analog(30.0f, AnalogQuality.AQ_ONLINE.swigValue().toShort), 0)
    }

    val analogUpdates = Await.result(analogUpdated, 5000.milliseconds)

    analogUpdates.size should equal(1)
    val analogUpdate = analogUpdates.head
    checkAnalog(analogUpdate, "Device01.Analog01", 30.0f)
  }

  def issueControlTest(session: Session, observer: SlaveObserver) {

    val modelClient = ModelService.client(session)
    val commandClient = CommandService.client(session)

    val commands = Await.result(modelClient.getCommands(EntityKeySet.newBuilder().addAllNames(allCommands.toSeq).build()), 5000.milliseconds)
    val commandNameMap = commands.map(c => (c.getName, c)).toMap

    val control01 = commandNameMap("Device01.Control01")

    val controlSelect = CommandSelect.newBuilder().addCommandUuids(control01.getUuid).build

    val selectResult = Await.result(commandClient.selectCommands(controlSelect), 5000.milliseconds)

    val request = Commands.CommandRequest.newBuilder().setCommandUuid(control01.getUuid).build()

    val response = Await.result(commandClient.issueCommandRequest(request), 5000.milliseconds)

    response.getStatus should equal(Commands.CommandStatus.SUCCESS)

    val cmdUpdates = observer.commandWatcher.currentUpdates()
    cmdUpdates.size should equal(1)
    val control = cmdUpdates.head.asInstanceOf[BinaryCommand]
    control.index should equal(0)
    control.code should equal(ControlCode.CC_PULSE)

    commandClient.deleteCommandLocks(List(selectResult.getId))
  }

  test("Command issuing") {
    val session = this.session.get

    issueControlTest(session, slaveObserver)
  }

  test("Setpoint issuing") {
    val session = this.session.get

    slaveObserver.commandWatcher.reset()

    val modelClient = ModelService.client(session)
    val commandClient = CommandService.client(session)

    val commands = Await.result(modelClient.getCommands(EntityKeySet.newBuilder().addAllNames(allCommands.toSeq).build()), 5000.milliseconds)
    val commandNameMap = commands.map(c => (c.getName, c)).toMap

    val setpoint01 = commandNameMap("Device01.Setpoint01")

    val select = CommandSelect.newBuilder().addCommandUuids(setpoint01.getUuid).build

    val selectResult = Await.result(commandClient.selectCommands(select), 5000.milliseconds)

    val request = Commands.CommandRequest.newBuilder()
      .setCommandUuid(setpoint01.getUuid)
      .setIntVal(120)
      .setType(Commands.CommandRequest.ValType.INT)
      .build()

    val response = Await.result(commandClient.issueCommandRequest(request), 5000.milliseconds)

    response.getStatus should equal(Commands.CommandStatus.SUCCESS)

    val cmdUpdates = slaveObserver.commandWatcher.currentUpdates()
    cmdUpdates.size should equal(1)
    val setpoint = cmdUpdates.head.asInstanceOf[SetpointCommand]
    setpoint.index should equal(0)
    setpoint.v should equal(120.toDouble)

  }

  test("Greenbus dropping doesn't affect DNP connection") {
    val dataObserver = this.slaveDataOpt.get
    val measWatcher = this.measWatcher.get
    measWatcher.watcher.reset()

    slaveObserver.stackWatcher.reset()

    val updateFut = measWatcher.watcher.future { l => l.length == 4 }

    dnpEndpointMgr.foreach { ref => Await.result(ask(ref, FrontEndSetManager.BusConnectionStop)(5000.milliseconds), 5000.milliseconds) }
    dnpEndpointMgr.foreach { _ ! FrontEndSetManager.BusConnectionStart }

    Range(0, 4).foreach { i =>
      observerTransaction(dataObserver) { obs =>
        obs.Update(new Analog(50.0f + i, AnalogQuality.AQ_ONLINE.swigValue().toShort), 1)
      }
      Thread.sleep(500)
    }

    val updates = Await.result(updateFut, 5000.milliseconds)

    updates.length should equal(4)
    updates.zipWithIndex.foreach {
      case (measNotification, i) =>
        measNotification.getPointName should equal("Device01.Analog02")
        measNotification.getValue.getDoubleVal should equal(50.0f + i)
    }

    slaveObserver.stackWatcher.currentUpdates() should equal(Nil)
    Thread.sleep(1000)
  }

  test("Swap targeted slave in config") {
    val session = this.session.get
    val observer = this.slaveObserver
    val observer2 = this.slaveObserver2
    val dataObserver = this.slaveDataOpt.get
    val dataObserver2 = this.slaveDataOpt2.get
    val measWatcher = this.measWatcher.get
    measWatcher.watcher.reset()

    observerTransaction(dataObserver2) { obs =>
      obs.Update(new Analog(88.0f, AnalogQuality.AQ_ONLINE.swigValue().toShort), 0)
    }

    observer.stackWatcher.reset()
    observer2.stackWatcher.reset()

    val modelClient = ModelService.client(session)

    val endpoint = Await.result(modelClient.getEndpoints(EntityKeySet.newBuilder().addNames("Endpoint01").build()), 500.milliseconds).head

    val cfgUpdate = EntityKeyValue.newBuilder()
      .setUuid(endpoint.getUuid)
      .setKey(Dnp3MasterProtocol.configKey)
      .setValue(
        StoredValue.newBuilder()
          .setByteArrayValue(
            ByteString.copyFrom(dnp3MasterConfig("Device01", 33005).getBytes("UTF-8"))))
      .build()

    val closeFut = observer.stackWatcher.future(_ == List(StackStates.SS_COMMS_DOWN))

    val openFut = observer2.stackWatcher.future(_ == List(StackStates.SS_COMMS_UP))

    val measFut = measWatcher.watcher.future { up => up.exists(_.getPointName == "Device01.Analog01") }

    val configPutResult = Await.result(modelClient.putEntityKeyValues(List(cfgUpdate)), 5000.milliseconds)
    configPutResult.size should equal(1)

    val bothFut = closeFut zip openFut

    val (closed, opened) = Await.result(bothFut, 5000.milliseconds)

    val initialMeasResults = Await.result(measFut, 5000.milliseconds)
    val m1 = initialMeasResults.find(_.getPointName == "Device01.Analog01").get
    m1.getValue.getDoubleVal should equal(88.0f)

    measWatcher.watcher.reset()

    val measFut2 = measWatcher.watcher.future { up => up.exists(_.getPointName == "Device01.Analog01") }

    observerTransaction(dataObserver2) { obs =>
      obs.Update(new Analog(89.0f, AnalogQuality.AQ_ONLINE.swigValue().toShort), 0)
    }

    val updatedMeasResults = Await.result(measFut2, 5000.milliseconds)
    val m2 = updatedMeasResults.find(_.getPointName == "Device01.Analog01").get
    m2.getValue.getDoubleVal should equal(89.0f)

    issueControlTest(session, slaveObserver2)

  }

}
