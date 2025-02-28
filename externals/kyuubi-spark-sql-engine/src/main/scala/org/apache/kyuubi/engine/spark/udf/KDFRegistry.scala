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

package org.apache.kyuubi.engine.spark.udf

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.{SparkEnv, TaskContext}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.udf

import org.apache.kyuubi.{KYUUBI_VERSION, Utils}
import org.apache.kyuubi.KyuubiSparkUtils.KYUUBI_SESSION_USER_KEY

object KDFRegistry {

  @transient
  val registeredFunctions = new ArrayBuffer[KyuubiDefinedFunction]()

  lazy val appName = SparkEnv.get.conf.get("spark.app.name")
  lazy val appId = SparkEnv.get.conf.get("spark.app.id")

  val kyuubi_version: KyuubiDefinedFunction = create(
    "kyuubi_version",
    udf(() => KYUUBI_VERSION).asNonNullable(),
    "Return the version of Kyuubi Server",
    "string",
    "1.3.0")

  val engine_name: KyuubiDefinedFunction = create(
    "engine_name",
    udf(() => appName).asNonNullable(),
    "Return the spark application name for the associated query engine",
    "string",
    "1.3.0"
  )

  val engine_id: KyuubiDefinedFunction = create(
    "engine_id",
    udf(() => appId).asNonNullable(),
    "Return the spark application id for the associated query engine",
    "string",
    "1.4.0"
  )

  val system_user: KyuubiDefinedFunction = create(
    "system_user",
    udf(() => Utils.currentUser).asNonNullable(),
    "Return the system user name for the associated query engine",
    "string",
    "1.3.0")

  val session_user: KyuubiDefinedFunction = create(
    "session_user",
    udf { () =>
      Option(TaskContext.get()).map(_.getLocalProperty(KYUUBI_SESSION_USER_KEY))
        .getOrElse(throw new RuntimeException("Unable to get session_user"))
    },
    "Return the session username for the associated query engine",
    "string",
    "1.4.0"
  )

  def create(
    name: String,
    udf: UserDefinedFunction,
    description: String,
    returnType: String,
    since: String): KyuubiDefinedFunction = {
    val kdf = KyuubiDefinedFunction(name, udf, description, returnType, since)
    registeredFunctions += kdf
    kdf
  }

  def registerAll(spark: SparkSession): Unit = {
    for (func <- registeredFunctions) {
      spark.udf.register(func.name, func.udf)
    }
  }
}
