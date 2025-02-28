/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.events

import java.io.{BufferedOutputStream, FileOutputStream, IOException, PrintWriter}
import java.net.URI

import scala.collection.mutable.HashMap

import org.apache.commons.lang3.StringUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, FSDataOutputStream, Path}
import org.apache.hadoop.fs.permission.FsPermission

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.{ConfigEntry, KyuubiConf}
import org.apache.kyuubi.config.KyuubiConf.ENGINE_EVENT_JSON_LOG_PATH
import org.apache.kyuubi.events.JsonEventLogger._
import org.apache.kyuubi.service.AbstractService

/**
 * This event logger logs Kyuubi engine events in JSON file format.
 * The hierarchical directory structure is:
 *   ${ENGINE_EVENT_JSON_LOG_PATH}/${eventType}/day=${date}/${logName}.json
 * The ${eventType} is based on core concepts of the Kyuubi systems, e.g. engine/session/statement
 * The ${date} is based on the time of events, e.g. engine.startTime, statement.startTime
 * @param logName the engine id formed of appId + attemptId(if any)
 */
class JsonEventLogger[T <: KyuubiEvent](logName: String,
    logPath: ConfigEntry[String], hadoopConf: Configuration)
  extends AbstractService("JsonEventLogger") with EventLogger[T] with Logging {

  type Logger = (PrintWriter, Option[FSDataOutputStream])

  private var logRoot: URI = _
  private var fs: FileSystem = _
  private val writers = HashMap.empty[String, Logger]

  private def getOrUpdate(event: KyuubiEvent): Logger = synchronized {
    val partitions = event.partitions.map(kv => s"${kv._1}=${kv._2}").mkString(Path.SEPARATOR)
    writers.getOrElseUpdate(event.eventType + partitions, {
      val eventPath = if (StringUtils.isEmpty(partitions)) {
        new Path(new Path(logRoot), event.eventType)
      } else {
        new Path(new Path(new Path(logRoot), event.eventType), partitions)
      }
      FileSystem.mkdirs(fs, eventPath, JSON_LOG_DIR_PERM)
      val logFile = new Path(eventPath, logName + ".json")
      var hadoopDataStream: FSDataOutputStream = null
      val rawStream = if (logFile.toUri.getScheme == "file") {
        new FileOutputStream(logFile.toUri.getPath)
      } else {
        hadoopDataStream = fs.create(logFile)
        hadoopDataStream
      }
      fs.setPermission(logFile, JSON_LOG_FILE_PERM)
      val bStream = new BufferedOutputStream(rawStream)
      info(s"Logging kyuubi events to $logFile")
      (new PrintWriter(bStream), Option(hadoopDataStream))
    })
  }

  private def requireLogRootWritable(): Unit = {
    val fileStatus = fs.getFileStatus(new Path(logRoot))
    if (!fileStatus.isDirectory) {
      throw new IllegalArgumentException(s"Log directory $logRoot is not a directory.")
    }
  }

  override def initialize(conf: KyuubiConf): Unit = synchronized {
    logRoot = URI.create(conf.get(logPath))
    fs = FileSystem.get(logRoot, hadoopConf)
    requireLogRootWritable()
    super.initialize(conf)
  }

  override def stop(): Unit = synchronized {
    writers.foreach { case (name, (writer, stream)) =>
      try {
        writer.close()
      } catch {
        case e: IOException => error(s"File to close $name's event writer", e)
      }
    }
    super.stop()
  }

  override def logEvent(kyuubiEvent: T): Unit = {
    val (writer, stream) = getOrUpdate(kyuubiEvent)
    // scalastyle:off println
    writer.println(kyuubiEvent.toJson)
    // scalastyle:on println
    writer.flush()
    stream.foreach(_.hflush())
  }

  // This method is only called by kyuubiServer
  def createEventLogRootDir(conf: KyuubiConf, hadoopConf: Configuration): Unit = {
    val logRoot: URI = URI.create(conf.get(ENGINE_EVENT_JSON_LOG_PATH))
    val fs: FileSystem = FileSystem.get(logRoot, hadoopConf)
    val success: Boolean = FileSystem.mkdirs(fs, new Path(logRoot), JSON_LOG_DIR_PERM)
    if (success == false) {
      val fileStatus = fs.getFileStatus(new Path(logRoot))
      if (!fileStatus.isDirectory) {
        throw new IllegalArgumentException(s"Log directory $logRoot is not a directory.")
      }
    }
  }
}

object JsonEventLogger {
  val JSON_LOG_DIR_PERM: FsPermission = new FsPermission(Integer.parseInt("770", 8).toShort)
  val JSON_LOG_FILE_PERM: FsPermission = new FsPermission(Integer.parseInt("660", 8).toShort)
}
