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
package io.greenbus.dnp3.integration.tools

import org.totalgrid.dnp3._
import io.greenbus.integration.tools.EventWatcher

object SlaveObserver {
  sealed trait Dnp3Command
  case class BinaryCommand(code: ControlCode, count: Short, offTime: Long, onTime: Long, index: Long) extends Dnp3Command
  case class SetpointCommand(v: Double, index: Long) extends Dnp3Command
}

class SlaveObserver {
  import SlaveObserver._

  val stackWatcher = new EventWatcher[StackStates]
  val commandWatcher = new EventWatcher[Dnp3Command]

  val stackObserver: IStackObserver = new IStackObserver {
    override def OnStateChange(aState: StackStates) {
      stackWatcher.enqueue(aState)
    }
  }

  val commandAcceptor: ICommandAcceptor = new ICommandAcceptor {
    override def AcceptCommand(arCommand: BinaryOutput, aIndex: Long, aSequence: Int, apRspAcceptor: IResponseAcceptor, isDirectOperate: Boolean) {
      commandWatcher.enqueue(BinaryCommand(arCommand.GetCode(), arCommand.getMCount, arCommand.getMOffTimeMS, arCommand.getMOnTimeMS, aIndex))
      apRspAcceptor.AcceptResponse(new CommandResponse(CommandStatus.CS_SUCCESS), aSequence)
    }

    override def AcceptCommand(arCommand: Setpoint, aIndex: Long, aSequence: Int, apRspAcceptor: IResponseAcceptor, isDirectOperate: Boolean) {
      commandWatcher.enqueue(SetpointCommand(arCommand.GetValue(), aIndex))
      apRspAcceptor.AcceptResponse(new CommandResponse(CommandStatus.CS_SUCCESS), aSequence)
    }
  }
}
