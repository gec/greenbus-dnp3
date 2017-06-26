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

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.client.service.proto.Commands
import org.totalgrid.dnp3._

import scala.concurrent.{ Future, Promise }
import io.greenbus.app.actor.frontend.ProtocolCommandAcceptor

sealed trait OutputMapping
case class ControlMapping(index: Int, cmdType: ControlCode, count: Option[Short], onTime: Option[Long], offTime: Option[Long], directOperate: Boolean) extends OutputMapping
case class SetpointMapping(index: Int, directOperate: Boolean) extends OutputMapping

object CommandAdapter {

  val notSupported = Commands.CommandResult.newBuilder().setStatus(Commands.CommandStatus.NOT_SUPPORTED).build()

}

class CommandAdapter(cmdAcceptor: ICommandAcceptor, mapping: Map[String, OutputMapping]) extends IResponseAcceptor with ProtocolCommandAcceptor with LazyLogging {
  import CommandAdapter._

  private var sequence = 0

  private var respMap = Map.empty[Int, Promise[Commands.CommandResult]]

  private def registerSequence(): (Int, Future[Commands.CommandResult]) = {
    val seq = sequence
    sequence += 1
    val promise = Promise[Commands.CommandResult]()
    respMap += (seq -> promise)
    (seq, promise.future)
  }

  def issue(commandName: String, request: Commands.CommandRequest): Future[Commands.CommandResult] = {
    mapping.get(commandName) match {
      case None =>
        logger.warn(s"Command $commandName not mapped")
        Future.successful(notSupported)
      case Some(cmdMapping) => cmdMapping match {
        case ControlMapping(index, cmdType, count, onTime, offTime, isDirectOperate) =>
          val (seq, fut) = registerSequence()

          val bo = new BinaryOutput(cmdType) //, count, onTime, offTime)
          count.foreach(bo.setMCount)
          onTime.foreach(bo.setMOnTimeMS)
          offTime.foreach(bo.setMOffTimeMS)

          cmdAcceptor.AcceptCommand(bo, index, seq, this, isDirectOperate)
          fut

        case SetpointMapping(index, isDirectOperate) =>
          request.getType match {
            case Commands.CommandRequest.ValType.NONE =>
              logger.warn(s"Command $commandName mapped to setpoint but has valtype NONE")
              Future.successful(notSupported)
            case Commands.CommandRequest.ValType.STRING =>
              logger.warn(s"Command $commandName mapped to setpoint but has valtype STRING")
              Future.successful(notSupported)
            case Commands.CommandRequest.ValType.INT =>
              val (seq, fut) = registerSequence()
              val setpoint = new Setpoint(request.getIntVal)
              cmdAcceptor.AcceptCommand(setpoint, index, seq, this, isDirectOperate)
              fut
            case Commands.CommandRequest.ValType.DOUBLE =>
              val (seq, fut) = registerSequence()
              val setpoint = new Setpoint(request.getDoubleVal)
              cmdAcceptor.AcceptCommand(setpoint, index, seq, this, isDirectOperate)
              fut
          }
      }
    }
  }

  override def AcceptResponse(arResponse: CommandResponse, aSequence: Int) {
    respMap.get(aSequence) match {
      case None => logger.warn("DNP command response with no matching sequence registered")
      case Some(promise) => {
        respMap -= aSequence
        val status = DNPTranslator.translateResponseToStatus(arResponse)
        val resp = Commands.CommandResult.newBuilder().setStatus(status).build()
        promise.success(resp)
      }
    }
  }
}
