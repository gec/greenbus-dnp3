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

import io.greenbus.client.service.proto.{ Measurements, Commands }
import org.totalgrid.dnp3._
import io.greenbus.client.service.proto.Commands.{ CommandStatus => ProtoCommandStatus }

object DNPTranslator {

  //it's also annoying to have to test bitmasks for != 0 in if statements
  private implicit def convertIntToBool(i: Int) = (i != 0)

  /* Translation functions from DNP3 type to proto measurements */

  def translate(v: Binary) = {
    translateCommon(v, translateBinaryQual) { m =>
      m.setType(Measurements.Measurement.Type.BOOL)
      m.setBoolVal(v.GetValue)
    }
  }

  def translate(v: Analog) = {
    translateCommon(v, translateAnalogQual) { m =>
      m.setType(Measurements.Measurement.Type.DOUBLE)
      m.setDoubleVal(v.GetValue)
    }
  }

  def translate(v: Counter) = {
    translateCommon(v, translateCounterQual) { m =>
      m.setType(Measurements.Measurement.Type.INT)
      m.setIntVal(v.GetValue)
    }
  }

  def translate(v: ControlStatus) = {
    translateCommon(v, translateControlQual) { m =>
      m.setType(Measurements.Measurement.Type.BOOL)
      m.setBoolVal(v.GetValue)
    }
  }

  def translate(v: SetpointStatus) = {
    translateCommon(v, translateSetpointQual) { m =>
      m.setType(Measurements.Measurement.Type.DOUBLE)
      m.setDoubleVal(v.GetValue)
    }
  }

  def translateResponseToStatus(rsp: CommandResponse) = {
    rsp.getMResult match {
      case CommandStatus.CS_SUCCESS => ProtoCommandStatus.SUCCESS
      case CommandStatus.CS_TIMEOUT => ProtoCommandStatus.TIMEOUT
      case CommandStatus.CS_NO_SELECT => ProtoCommandStatus.NO_SELECT
      case CommandStatus.CS_FORMAT_ERROR => ProtoCommandStatus.FORMAT_ERROR
      case CommandStatus.CS_NOT_SUPPORTED => ProtoCommandStatus.NOT_SUPPORTED
      case CommandStatus.CS_ALREADY_ACTIVE => ProtoCommandStatus.ALREADY_ACTIVE
      case CommandStatus.CS_HARDWARE_ERROR => ProtoCommandStatus.HARDWARE_ERROR
      case CommandStatus.CS_LOCAL => ProtoCommandStatus.LOCAL
      case CommandStatus.CS_TOO_MANY_OPS => ProtoCommandStatus.TOO_MANY_OPS
      case CommandStatus.CS_NOT_AUTHORIZED => ProtoCommandStatus.NOT_AUTHORIZED
      case _ => ProtoCommandStatus.UNDEFINED
    }
  }
  def translateCommandStatus(rsp: ProtoCommandStatus): CommandStatus = {
    rsp match {
      case ProtoCommandStatus.SUCCESS => CommandStatus.CS_SUCCESS
      case ProtoCommandStatus.TIMEOUT => CommandStatus.CS_TIMEOUT
      case ProtoCommandStatus.NO_SELECT => CommandStatus.CS_NO_SELECT
      case ProtoCommandStatus.FORMAT_ERROR => CommandStatus.CS_FORMAT_ERROR
      case ProtoCommandStatus.NOT_SUPPORTED => CommandStatus.CS_NOT_SUPPORTED
      case ProtoCommandStatus.ALREADY_ACTIVE => CommandStatus.CS_ALREADY_ACTIVE
      case ProtoCommandStatus.HARDWARE_ERROR => CommandStatus.CS_HARDWARE_ERROR
      case ProtoCommandStatus.LOCAL => CommandStatus.CS_LOCAL
      case ProtoCommandStatus.TOO_MANY_OPS => CommandStatus.CS_TOO_MANY_OPS
      case ProtoCommandStatus.NOT_AUTHORIZED => CommandStatus.CS_NOT_AUTHORIZED
      case _ => CommandStatus.CS_UNDEFINED
    }
  }

  /* Translation functions from bus CommandRequests to DNP3 types */

  def translateSetpoint(c: Commands.CommandRequest) = c.getType match {
    case Commands.CommandRequest.ValType.INT => new Setpoint(c.getIntVal)
    case Commands.CommandRequest.ValType.DOUBLE => new Setpoint(c.getDoubleVal)
    case _ => throw new Exception("wrong type for setpoint")
  }

  /* private helper functions */

  private def translateCommon(v: DataPoint, q: Short => Measurements.Quality.Builder)(f: Measurements.Measurement.Builder => Unit) = {
    val m = Measurements.Measurement.newBuilder
      .setQuality(q(v.GetQuality)) // apply the specified quality conversion function
    // we only set the time on the proto if the protocol gave us a valid time
    val t = v.GetTime
    if (t != 0) {
      m.setTime(t)
      m.setIsDeviceTime(true)
    }
    f(m) // apply the specified measurement building function
    m.build
  }

  private def translateBinaryQual(q: Short) = {
    translateQual(q, BinaryQuality.BQ_STATE.swigValue()) { dqual =>
      if (q & BinaryQuality.BQ_CHATTER_FILTER.swigValue()) dqual.setOscillatory(true)
    }
  }

  private def translateAnalogQual(q: Short) = {
    translateQual(q, 0) { dqual =>
      if (q & AnalogQuality.AQ_OVERRANGE.swigValue()) dqual.setOverflow(true)
      if (q & AnalogQuality.AQ_REFERENCE_CHECK.swigValue()) dqual.setBadReference(true)
    }
  }

  private def translateCounterQual(q: Short) = translateQuality(q, 0)
  private def translateSetpointQual(q: Short) = translateQuality(q, 0)
  private def translateControlQual(q: Short) = translateQuality(q, ControlQuality.TQ_STATE.swigValue())

  private def translateQuality(q: Short, validBits: Int) = {
    val qual = getQual(q, validBits)
    val dqual = getDetailQual(q)
    buildQual(qual, dqual)
  }

  private def translateQual(q: Short, validBits: Int)(f: (Measurements.DetailQual.Builder) => Unit) = {
    val qual = getQual(q, validBits)
    val dqual = getDetailQual(q)
    f(dqual)
    buildQual(qual, dqual)
  }

  private def buildQual(q: Measurements.Quality.Builder, dq: Measurements.DetailQual.Builder) = {
    q.setDetailQual(dq)
    q
  }

  private def getDetailQual(q: Short) = {
    val dqual = Measurements.DetailQual.newBuilder
    val old = BinaryQuality.BQ_RESTART.swigValue() | BinaryQuality.BQ_RESTART.swigValue()
    if (q & old) dqual.setOldData(true)
    dqual
  }

  private def getQual(q: Short, validbits: Int) = {

    val substituted = BinaryQuality.BQ_REMOTE_FORCED_DATA.swigValue() | BinaryQuality.BQ_LOCAL_FORCED_DATA.swigValue()
    //ignore online, substituted bits when determined validity
    val valid = validbits | BinaryQuality.BQ_ONLINE.swigValue() | substituted

    val qual = Measurements.Quality.newBuilder
    if (q & ~valid) qual.setValidity(Measurements.Quality.Validity.INVALID)
    if (q & substituted) qual.setSource(Measurements.Quality.Source.SUBSTITUTED)
    qual
  }

}