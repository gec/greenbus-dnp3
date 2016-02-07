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
package io.greenbus.dnp3.common

import org.totalgrid.dnp3.{ IStackObserver, StackStates }
import io.greenbus.client.service.proto.FrontEnd.FrontEndConnectionStatus

trait StackObserver {
  def update(state: FrontEndConnectionStatus.Status)
}

class StackAdapter(obs: StackObserver) extends IStackObserver {
  override def OnStateChange(aState: StackStates) {
    obs.update(StackAdapter.translate(aState))
  }
}

object StackAdapter {

  def translate(state: StackStates): FrontEndConnectionStatus.Status = {
    state match {
      case StackStates.SS_COMMS_DOWN => FrontEndConnectionStatus.Status.COMMS_DOWN
      case StackStates.SS_COMMS_UP => FrontEndConnectionStatus.Status.COMMS_UP
      case StackStates.SS_UNKNOWN => FrontEndConnectionStatus.Status.UNKNOWN
    }
  }

}
