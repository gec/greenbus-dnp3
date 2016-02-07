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
package io.greenbus.dnp3.master

import io.greenbus.dnp3.xml._

import scala.collection.JavaConversions._

import org.totalgrid.dnp3._
import javax.xml.bind.JAXBContext
import java.io.ByteArrayInputStream
import io.greenbus.dnp3.common.XmlToProtoTranslations
import io.greenbus.dnp3.Dnp3MasterConfig

/**
 * Converts xml for the master stack to the dnp3 configuration objects
 */
object MasterXmlConfig {

  def loadConfig(xml: DNPMasterEndpoint): (Dnp3MasterConfig, IndexMapping, Map[String, OutputMapping]) = {

    val filter = Option(xml.getLog)
      .flatMap(log => Option(log.getFilter))
      .map(XmlToProtoTranslations.translateFilterLevel)
      .getOrElse(FilterLevel.LEV_WARNING)

    if (xml.getMaster == null) {
      throw new IllegalArgumentException("Configuration must include master configuration")
    }

    val master = loadConfig(xml.getMaster)

    val address = Option(xml.getTCPClient).flatMap(client => Option(client.getAddress)).getOrElse(throw new IllegalArgumentException("Configuration must include address"))
    val port = Option(xml.getTCPClient).flatMap(client => Option(client.getPort)).getOrElse(throw new IllegalArgumentException("Configuration must include port"))
    val retryMsOpt = Option(xml.getTCPClient).map(client => client.getOpenRetryMS)

    val retryMs = retryMsOpt match {
      case None | Some(0) => 5000
      case Some(v) => v
    }

    if (xml.getIndexMapping == null) {
      throw new IllegalArgumentException("Configuration must include mapping")
    }

    val (indexMapping, controlMapping) = loadMapping(xml.getIndexMapping)

    (Dnp3MasterConfig(master, filter, address, port, retryMs), indexMapping, controlMapping)
  }

  def loadMapping(xml: io.greenbus.dnp3.xml.IndexMapping): (io.greenbus.dnp3.master.IndexMapping, Map[String, OutputMapping]) = {

    val binaries = Option(xml.getBinaries).map(_.getMapping.toSeq.map(m => (m.getIndex.toLong, m.getName))).getOrElse(Nil).toMap
    val analogs = Option(xml.getAnalogs).map(_.getMapping.toSeq.map(m => (m.getIndex.toLong, m.getName))).getOrElse(Nil).toMap
    val counters = Option(xml.getCounters).map(_.getMapping.toSeq.map(m => (m.getIndex.toLong, m.getName))).getOrElse(Nil).toMap
    val controlStatuses = Option(xml.getControlStatuses).map(_.getMapping.toSeq.map(m => (m.getIndex.toLong, m.getName))).getOrElse(Nil).toMap
    val setpointStatuses = Option(xml.getSetpointStatuses).map(_.getMapping.toSeq.map(m => (m.getIndex.toLong, m.getName))).getOrElse(Nil).toMap

    val controls: Seq[(String, ControlMapping)] =
      Option(xml.getControls).map(_.getMapping.toSeq.map(m => (m.getName, loadControl(m)))).getOrElse(Nil)

    val setpoints: Seq[(String, SetpointMapping)] =
      Option(xml.getSetpoints).map(_.getMapping.toSeq.map(m => (m.getName, loadSetpoint(m)))).getOrElse(Nil)

    val controlMap = (controls ++ setpoints).toMap

    val indexMap = io.greenbus.dnp3.master.IndexMapping(binaries, analogs, counters, controlStatuses, setpointStatuses)

    (indexMap, controlMap)
  }

  def loadSetpoint(mapping: io.greenbus.dnp3.xml.IndexMapping.Setpoints.Mapping): SetpointMapping = {
    val isDirectOperate = Option(mapping.getFunction).exists(_ == FunctionType.DIRECT_OPERATE)
    SetpointMapping(mapping.getIndex.toInt, isDirectOperate)

  }

  def loadControl(mapping: io.greenbus.dnp3.xml.IndexMapping.Controls.Mapping): ControlMapping = {
    val controlOpts: Option[ControlOptions] = Option(mapping.getControlOptions)
    val count = controlOpts.flatMap(opts => Option(opts.getCount)).map(_.toShort)
    val onTime = controlOpts.flatMap(opts => Option(opts.getOnTime)).map(_.toLong)
    val offTime = controlOpts.flatMap(opts => Option(opts.getOffTime)).map(_.toLong)

    val typ = controlOpts.flatMap(opts => Option(opts.getType)).map(translateCommandType).getOrElse(ControlCode.CC_PULSE)

    val isDirectOperate = Option(mapping.getFunction).exists(_ == FunctionType.DIRECT_OPERATE)

    ControlMapping(mapping.getIndex.toInt, typ, count, onTime, offTime, isDirectOperate)
  }

  def translateCommandType(c: ControlType) = c match {
    case ControlType.LATCH_ON => ControlCode.CC_LATCH_ON
    case ControlType.LATCH_OFF => ControlCode.CC_LATCH_OFF
    case ControlType.PULSE => ControlCode.CC_PULSE
    case ControlType.PULSE_CLOSE => ControlCode.CC_PULSE_CLOSE
    case ControlType.PULSE_TRIP => ControlCode.CC_PULSE_TRIP
    case _ => throw new IllegalArgumentException("Invalid Command code")
  }

  def loadConfig(xml: Master): MasterStackConfig = {
    val config = new MasterStackConfig
    config.setMaster(configure(xml, xml.getStack.getAppLayer.getMaxFragSize))
    config.setApp(configure(xml.getStack.getAppLayer))
    config.setLink(configure(xml.getStack.getLinkLayer))
    config
  }

  def configure(xml: LinkLayer): LinkConfig = {
    val cfg = new LinkConfig(xml.isIsMaster, xml.isUseConfirmations)
    cfg.setNumRetry(xml.getNumRetries)
    cfg.setRemoteAddr(xml.getRemoteAddress)
    cfg.setLocalAddr(xml.getLocalAddress)
    cfg.setTimeout(xml.getAckTimeoutMS)
    cfg
  }

  def configure(xml: AppLayer): AppConfig = {
    val cfg = new AppConfig
    cfg.setFragSize(xml.getMaxFragSize)
    cfg.setRspTimeout(xml.getTimeoutMS)
    cfg
  }

  private def configure(xml: Master, fragSize: Int): MasterConfig = {
    val cfg = new MasterConfig
    cfg.setAllowTimeSync(xml.getMasterSettings.isAllowTimeSync)
    cfg.setTaskRetryRate(xml.getMasterSettings.getTaskRetryMS)
    cfg.setIntegrityRate(xml.getMasterSettings.getIntegrityPeriodMS)

    cfg.setDoUnsolOnStartup(xml.getUnsol.isDoTask)
    cfg.setEnableUnsol(xml.getUnsol.isEnable)

    var unsol_class = 0
    if (xml.getUnsol.isClass1) unsol_class = unsol_class | PointClass.PC_CLASS_1.swigValue
    if (xml.getUnsol.isClass2) unsol_class = unsol_class | PointClass.PC_CLASS_2.swigValue
    if (xml.getUnsol.isClass3) unsol_class = unsol_class | PointClass.PC_CLASS_3.swigValue
    cfg.setUnsolClassMask(unsol_class)

    cfg.setFragSize(fragSize)

    xml.getScanList.getExceptionScan.foreach { scan =>
      var point_class = PointClass.PC_CLASS_0.swigValue
      if (scan.isClass1) point_class = point_class | PointClass.PC_CLASS_1.swigValue
      if (scan.isClass2) point_class = point_class | PointClass.PC_CLASS_2.swigValue
      if (scan.isClass3) point_class = point_class | PointClass.PC_CLASS_3.swigValue
      cfg.AddExceptionScan(point_class, scan.getPeriodMS)
    }

    cfg
  }
}

class Dnp3XmlConfigurer {
  import MasterXmlConfig._

  private val jaxbContext = JAXBContext.newInstance("io.greenbus.dnp3.xml")

  def readMasterConfig(bytes: Array[Byte]): (Dnp3MasterConfig, io.greenbus.dnp3.master.IndexMapping, Map[String, OutputMapping]) = {
    val um = jaxbContext.createUnmarshaller
    val is = new ByteArrayInputStream(bytes)

    val obj = um.unmarshal(is)
    val xml = obj.asInstanceOf[DNPMasterEndpoint]

    loadConfig(xml)
  }
}
