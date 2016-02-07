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

import io.greenbus.dnp3.xml.{ LogLevel, Log }
import org.totalgrid.dnp3._
import io.greenbus.dnp3.xml.{ Log, LogLevel }

/**
 * Converts common dnp3 xml settings to their proto or dnp3 equivilants
 */
object XmlToProtoTranslations {

  def filterLevel(log: Log) = {
    Option(log).map { l => translateFilterLevel(l.getFilter) }.getOrElse(FilterLevel.LEV_WARNING)
  }

  def translateFilterLevel(xmlLevel: LogLevel): FilterLevel = {
    xmlLevel match {
      case LogLevel.LOG_EVENT => FilterLevel.LEV_EVENT
      case LogLevel.LOG_ERROR => FilterLevel.LEV_ERROR
      case LogLevel.LOG_WARNING => FilterLevel.LEV_WARNING
      case LogLevel.LOG_INFO => FilterLevel.LEV_INFO
      case LogLevel.LOG_INTERPRET => FilterLevel.LEV_INTERPRET
      case LogLevel.LOG_COMM => FilterLevel.LEV_COMM
      case LogLevel.LOG_DEBUG => FilterLevel.LEV_DEBUG
      case _ => FilterLevel.LEV_WARNING
    }
  }
}