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

import akka.pattern.ask
import com.typesafe.scalalogging.LazyLogging
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{ BeforeAndAfterEach, FunSuite, Matchers }
import org.totalgrid.dnp3.StackStates
import io.greenbus.msg.Session
import io.greenbus.app.actor.frontend.FrontEndSetManager
import io.greenbus.client.service.ModelService
import io.greenbus.client.service.proto.ModelRequests.{ EndpointDisabledUpdate, EntityKeyPair, EntityKeySet }
import io.greenbus.dnp3.Dnp3MasterProtocol

import scala.concurrent.Await
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class EndpointDroppingTests extends FunSuite with Matchers with LazyLogging with BeforeAndAfterEach {

  var currentFixture = Option.empty[Dnp3Fixture]

  override protected def afterEach() {
    currentFixture.foreach(_.shutdown())
  }

  def systemChangeWhileUpTest(changeSystem: Session => Unit) {
    val f = new Dnp3Fixture
    currentFixture = Some(f)
    f.init()

    f.slaveObserver.stackWatcher.reset()
    val stackChangeFut = f.slaveObserver.stackWatcher.future(_.nonEmpty)

    val session = f.session.get
    val connMgr = f.dnpEndpointMgr.get

    f.slaveObserver.stackWatcher.currentUpdates() should equal(Nil)

    changeSystem(session)

    val stackChange = Await.result(stackChangeFut, 10000.milliseconds)
    stackChange should equal(List(StackStates.SS_COMMS_DOWN))

  }

  def deleteConfiguration(session: Session) {
    val modelClient = ModelService.client(session)
    val endpoint = Await.result(modelClient.getEndpoints(EntityKeySet.newBuilder().addNames("Endpoint01").build()), 500.milliseconds).head
    val keyPair = EntityKeyPair.newBuilder().setUuid(endpoint.getUuid).setKey(Dnp3MasterProtocol.configKey).build()

    val deleteResult = Await.result(modelClient.deleteEntityKeyValues(Seq(keyPair)), 10000.milliseconds)
    deleteResult.size should equal(1)
  }

  def disableEndpoint(session: Session) {
    val modelClient = ModelService.client(session)
    val endpoints = Await.result(modelClient.getEndpoints(EntityKeySet.newBuilder.addNames("Endpoint01").build()), 10000.milliseconds)
    endpoints.size should equal(1)
    val endpoint = endpoints.head

    val update = EndpointDisabledUpdate.newBuilder().setEndpointUuid(endpoint.getUuid).setDisabled(true).build()

    val deleteResult = Await.result(modelClient.putEndpointDisabled(List(update)), 10000.milliseconds)
    deleteResult.size should equal(1)
  }

  def deleteEndpoint(session: Session) {
    val modelClient = ModelService.client(session)
    val endpoints = Await.result(modelClient.getEndpoints(EntityKeySet.newBuilder.addNames("Endpoint01").build()), 10000.milliseconds)
    endpoints.size should equal(1)
    val endpoint = endpoints.head

    val deleteResult = Await.result(modelClient.deleteEndpoints(List(endpoint.getUuid)), 10000.milliseconds)
    deleteResult.size should equal(1)
  }

  test("Configuration deleted while Greenbus is up") {
    systemChangeWhileUpTest(deleteConfiguration)
  }

  test("Endpoint disabled while Greenbus conn up") {
    systemChangeWhileUpTest(disableEndpoint)
  }

  test("Endpoint deleted while Greenbus conn up") {
    systemChangeWhileUpTest(deleteEndpoint)
  }

  def systemChangeWhileDownTest(changeSystem: Session => Unit) {
    val f = new Dnp3Fixture
    currentFixture = Some(f)
    f.init()

    f.slaveObserver.stackWatcher.reset()
    val stackChangeFut = f.slaveObserver.stackWatcher.future(_.nonEmpty)

    val session = f.session.get
    val endMgr = f.dnpEndpointMgr.get

    Await.result(ask(endMgr, FrontEndSetManager.BusConnectionStop)(10000.milliseconds), 10000.milliseconds)

    f.slaveObserver.stackWatcher.currentUpdates() should equal(Nil)

    changeSystem(session)

    endMgr ! FrontEndSetManager.BusConnectionStart

    val stackChange = Await.result(stackChangeFut, 10000.milliseconds)
    stackChange should equal(List(StackStates.SS_COMMS_DOWN))

  }

  test("Configuration deleted while Greenbus conn down") {
    systemChangeWhileDownTest(deleteConfiguration)
  }

  test("Endpoint disabled while Greenbus conn down") {
    systemChangeWhileDownTest(disableEndpoint)
  }

  test("Endpoint deleted while Greenbus conn down") {
    systemChangeWhileDownTest(deleteEndpoint)
  }

}