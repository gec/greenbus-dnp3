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

import com.typesafe.scalalogging.slf4j.Logging
import io.greenbus.client.service.proto.Processing.Filter.FilterType
import io.greenbus.client.service.proto.Processing.{ TriggerSet, Action, Filter, Trigger }
import io.greenbus.loader.set.Actions._
import io.greenbus.loader.set.Mdl.EdgeDesc
import io.greenbus.loader.set.{ ByteArrayValue, NamedEntId, Mdl, Upload }
import io.greenbus.msg.Session
import org.totalgrid.dnp3._
import io.greenbus.client.service.proto.{ Processing, Model }
import io.greenbus.dnp3.Dnp3MasterProtocol

object Dnp3IntegrationConfiguration extends Logging {

  def loadSlave(suffix: String, port: Int, stackManager: StackManager, cmdAcceptor: ICommandAcceptor, stackObserver: Option[IStackObserver]): IDataObserver = {

    stackManager.AddTCPServer("slaveServer" + suffix, new PhysLayerSettings(FilterLevel.LEV_INFO, 2000), "127.0.0.1", port)

    val stackConfig = new SlaveStackConfig()

    val cfg = new SlaveConfig()
    stackObserver.foreach(cfg.setMpObserver)

    stackConfig.setSlave(cfg)

    val template = new DeviceTemplate()
    template.getMBinary.add(new EventPointRecord("bin01", PointClass.PC_CLASS_1))
    template.getMBinary.add(new EventPointRecord("bin02", PointClass.PC_CLASS_1))
    template.getMAnalog.add(new DeadbandPointRecord("analog01", PointClass.PC_CLASS_1, 0.01f))
    template.getMAnalog.add(new DeadbandPointRecord("analog02", PointClass.PC_CLASS_1, 0.01f))

    template.getMControls.add(new ControlRecord("control01"))
    template.getMControls.add(new ControlRecord("control02"))
    template.getMSetpoints.add(new ControlRecord("setpoint01"))
    template.getMSetpoints.add(new ControlRecord("setpoint02"))

    stackConfig.setDevice(template)

    stackManager.AddSlave("slaveServer" + suffix, "slave" + suffix, FilterLevel.LEV_INFO, cmdAcceptor, stackConfig)
  }

  val statusPoints = Set("Device01.Status01", "Device01.Status02")
  val analogPoints = Set("Device01.Analog01", "Device01.Analog02")
  val allPoints = statusPoints union analogPoints

  val controls = Set("Device01.Control01", "Device01.Control02")
  val setpoints = Set("Device01.Setpoint01", "Device01.Setpoint02")
  val allCommands = controls union setpoints

  def loadActions(actionSet: ActionsList, session: Session): Unit = {
    Upload.push(session, actionSet, Seq())
  }

  def filterTrigger() = {

    val trigger = Trigger.newBuilder()
      .setFilter(Filter.newBuilder().setType(FilterType.DUPLICATES_ONLY))
      .addActions(Action.newBuilder()
        .setActionName("Filter")
        .setType(Processing.ActivationType.LOW)
        .setSuppress(true))
      .build()

    TriggerSet.newBuilder().addTriggers(trigger).build()
  }

  def buildConfig(deviceName: String, endpointName: String, port: Int, protocol: String): ActionsList = {
    import Mdl._

    val cache = new ActionCache

    cache.entityPuts += PutEntity(None, deviceName, Set("Equipment"))

    cache.pointPuts += PutPoint(None, s"$deviceName.Status01", Set("Status"), Model.PointCategory.STATUS, "status")
    cache.pointPuts += PutPoint(None, s"$deviceName.Status02", Set("Status"), Model.PointCategory.STATUS, "status")

    cache.pointPuts += PutPoint(None, s"$deviceName.Analog01", Set("Analog"), Model.PointCategory.ANALOG, "kW")
    cache.pointPuts += PutPoint(None, s"$deviceName.Analog02", Set("Analog"), Model.PointCategory.ANALOG, "V")

    cache.commandPuts += PutCommand(None, s"$deviceName.Control01", Set("Control"), Model.CommandCategory.CONTROL, s"$deviceName.Control01")
    cache.commandPuts += PutCommand(None, s"$deviceName.Control02", Set("Control"), Model.CommandCategory.CONTROL, s"$deviceName.Control02")

    cache.commandPuts += PutCommand(None, s"$deviceName.Setpoint01", Set("Setpoint"), Model.CommandCategory.SETPOINT_DOUBLE, s"$deviceName.Setpoint01")
    cache.commandPuts += PutCommand(None, s"$deviceName.Setpoint02", Set("Setpoint"), Model.CommandCategory.SETPOINT_DOUBLE, s"$deviceName.Setpoint02")

    cache.endpointPuts += PutEndpoint(None, endpointName, Set(), protocol)

    val pointNames = cache.pointPuts.result().map(_.name)
    val commandNames = cache.commandPuts.result().map(_.name)
    val pointAndCommandNames = pointNames ++ commandNames
    pointAndCommandNames.foreach { n =>
      cache.edgePuts += PutEdge(EdgeDesc(NamedEntId(deviceName), "owns", NamedEntId(n)))
      cache.edgePuts += PutEdge(EdgeDesc(NamedEntId(endpointName), "source", NamedEntId(n)))
    }

    val modbusConfig = dnp3MasterConfig(deviceName, port)
    cache.keyValuePutByNames += PutKeyValueByName(endpointName, "protocolConfig", ByteArrayValue(modbusConfig.getBytes("UTF-8")))

    val filterBytes = filterTrigger().toByteArray
    pointNames.foreach { name =>
      cache.keyValuePutByNames += PutKeyValueByName(name, "triggerSet", ByteArrayValue(filterBytes))
    }

    cache.result()
  }

  /*def masterConfig(deviceName: String, endpointName: String, port: Int): LoaderSet = {

    val equip = LdrEntity(deviceName, List("Equipment"))

    val status01 = LdrPoint(s"$deviceName.Status01", Model.PointCategory.STATUS, "status", List("Status"))
    val status02 = LdrPoint(s"$deviceName.Status02", Model.PointCategory.STATUS, "status", List("Status"))

    val analog01 = LdrPoint(s"$deviceName.Analog01", Model.PointCategory.ANALOG, "kW", List("Analog"))
    val analog02 = LdrPoint(s"$deviceName.Analog02", Model.PointCategory.ANALOG, "V", List("Analog"))

    val control01 = LdrCommand(s"$deviceName.Control01", s"$deviceName.Control01", Model.CommandCategory.CONTROL, List("Control"))
    val control02 = LdrCommand(s"$deviceName.Control02", s"$deviceName.Control02", Model.CommandCategory.CONTROL, List("Control"))

    val setpoint01 = LdrCommand(s"$deviceName.Setpoint01", s"$deviceName.Setpoint01", Model.CommandCategory.SETPOINT_DOUBLE, List("Setpoint"))
    val setpoint02 = LdrCommand(s"$deviceName.Setpoint02", s"$deviceName.Setpoint02", Model.CommandCategory.SETPOINT_DOUBLE, List("Setpoint"))

    val points = List(status01, status02, analog01, analog02)
    val commands = List(control01, control02, setpoint01, setpoint02)
    val allFrontendObjs = points ::: commands

    val equipAssocs = allFrontendObjs.map(e => AssocEquipmentHierarchy(equip.name, e.name))

    val endpoint = LdrEndpoint(endpointName, "dnp3")

    val dnpConfig = dnp3MasterConfig(deviceName, port)

    val configFile = LoadedConfigFile(endpointName, Dnp3MasterProtocol.configKey, dnpConfig.getBytes("UTF-8"))

    val endpointAssocs = allFrontendObjs.map(e => AssocEndpoint(endpoint.name, e.name))

    val triggers = points.map(p => LdrTriggerSet(p.name, List(TriggerCommon.filterDefault(p.name)).map(_.build())))

    LoaderSet(
      entities = List(equip),
      points = points,
      commands = commands,
      endpoints = List(endpoint),
      configFiles = List(configFile),
      triggerSets = triggers,
      assocEquipmentHierarchys = equipAssocs,
      assocEndpoint = endpointAssocs)
  }*/

  def dnp3MasterConfig(deviceName: String, port: Int) =
    s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      |<DNPMasterEndpoint xmlns:dnp3="APLXML.DNP" xmlns:apl="APLXML.Base" xmlns="io.greenbus.dnp3">
      |	<dnp3:Master>
      |		<dnp3:Stack>
      |			<dnp3:LinkLayer RemoteBuffFullTimeoutMS="0" NumRetries="3" AckTimeoutMS="1000" UseConfirmations="false" RemoteAddress="1024" LocalAddress="1" IsMaster="true"/>
      |			<dnp3:AppLayer NumRetries="0" MaxFragSize="2048" TimeoutMS="5000"/>
      |		</dnp3:Stack>
      |		<dnp3:MasterSettings IntegrityPeriodMS="300000" TaskRetryMS="5000" AllowTimeSync="true"/>
      |		<dnp3:ScanList>
      |		  <dnp3:ExceptionScan PeriodMS="2000" Class3="true" Class2="true" Class1="true"/>
      |		</dnp3:ScanList>
      |		<dnp3:Unsol Class3="true" Class2="true" Class1="true" Enable="false" DoTask="false"/>
      |	</dnp3:Master>
      |	<apl:TCPClient Address="127.0.0.1" Port="$port" />
      |	<IndexMapping>
      |		<Binaries>
      |			<Mapping index="0" name="$deviceName.Status01" />
      |			<Mapping index="1" name="$deviceName.Status02" />
      |		</Binaries>
      |		<Analogs>
      |			<Mapping index="0" name="$deviceName.Analog01" />
      |			<Mapping index="1" name="$deviceName.Analog02" />
      |		</Analogs>
      |		<Controls>
      |			<Mapping index="0" name="$deviceName.Control01">
      |       <ControlOptions type="PULSE" />
      |     </Mapping>
      |			<Mapping index="1" name="$deviceName.Control02" />
      |		</Controls>
      |		<Setpoints>
      |			<Mapping index="0" name="$deviceName.Setpoint01" />
      |			<Mapping index="1" name="$deviceName.Setpoint02" />
      |		</Setpoints>
      |	</IndexMapping>
      |</DNPMasterEndpoint>
    """.stripMargin

}
