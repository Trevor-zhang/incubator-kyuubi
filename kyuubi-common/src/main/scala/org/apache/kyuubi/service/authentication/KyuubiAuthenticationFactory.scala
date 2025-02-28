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

package org.apache.kyuubi.service.authentication

import java.io.IOException
import javax.security.auth.login.LoginException
import javax.security.sasl.Sasl

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.security.UserGroupInformation
import org.apache.hadoop.security.authentication.util.KerberosName
import org.apache.hadoop.security.authorize.ProxyUsers
import org.apache.hive.service.rpc.thrift.TCLIService.Iface
import org.apache.thrift.TProcessorFactory
import org.apache.thrift.transport.{TSaslServerTransport, TTransportException, TTransportFactory}

import org.apache.kyuubi.{KyuubiSQLException, Logging}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.service.authentication.AuthTypes._

class KyuubiAuthenticationFactory(conf: KyuubiConf) extends Logging {

  private val authTypes = conf.get(AUTHENTICATION_METHOD).map(AuthTypes.withName)
  private val noSasl = authTypes == Seq(NOSASL)
  private val kerberosEnabled = authTypes.contains(KERBEROS)
  private val plainAuthTypeOpt = authTypes.filterNot(_.equals(KERBEROS))
    .filterNot(_.equals(NOSASL)).headOption

  private val hadoopAuthServer: Option[HadoopThriftAuthBridgeServer] = {
    if (kerberosEnabled) {
      val secretMgr = KyuubiDelegationTokenManager(conf)
      try {
        secretMgr.startThreads()
      } catch {
        case e: IOException => throw new TTransportException("Failed to start token manager", e)
      }
      Some(new HadoopThriftAuthBridgeServer(secretMgr))
    } else {
      None
    }
  }

  private def getSaslProperties: java.util.Map[String, String] = {
    val props = new java.util.HashMap[String, String]()
    val qop = SaslQOP.withName(conf.get(SASL_QOP))
    props.put(Sasl.QOP, qop.toString)
    props.put(Sasl.SERVER_AUTH, "true")
    props
  }

  def getTTransportFactory: TTransportFactory = {
    if (noSasl) {
      new TTransportFactory()
    } else {
      var transportFactory: TSaslServerTransport.Factory = null

      hadoopAuthServer match {
        case Some(server) =>
          transportFactory = try {
            server.createSaslServerTransportFactory(getSaslProperties)
          } catch {
            case e: TTransportException => throw new LoginException(e.getMessage)
          }

        case _ =>
      }

      plainAuthTypeOpt match {
        case Some(plainAuthType) =>
          transportFactory = PlainSASLHelper.getTransportFactory(plainAuthType.toString, conf,
            Option(transportFactory)).asInstanceOf[TSaslServerTransport.Factory]

        case _ =>
      }

      hadoopAuthServer match {
        case Some(server) => server.wrapTransportFactory(transportFactory)
        case _ => transportFactory
      }
    }
  }

  def getTProcessorFactory(fe: Iface): TProcessorFactory = hadoopAuthServer match {
    case Some(server) => FEServiceProcessorFactory(server, fe)
    case _ => PlainSASLHelper.getProcessFactory(fe)
  }

  def getRemoteUser: Option[String] = {
    hadoopAuthServer.map(_.getRemoteUser).orElse(Option(TSetIpAddressProcessor.getUserName))
  }

  def getIpAddress: Option[String] = {
    hadoopAuthServer.map(_.getRemoteAddress).map(_.getHostAddress)
      .orElse(Option(TSetIpAddressProcessor.getUserIpAddress))
  }
}
object KyuubiAuthenticationFactory {
  val HS2_PROXY_USER = "hive.server2.proxy.user"

  @throws[KyuubiSQLException]
  def verifyProxyAccess(
      realUser: String,
      proxyUser: String,
      ipAddress: String,
      hadoopConf: Configuration): Unit = {
    try {
      val sessionUgi = {
        if (UserGroupInformation.isSecurityEnabled) {
          val kerbName = new KerberosName(realUser)
          UserGroupInformation.createProxyUser(
            kerbName.getServiceName,
            UserGroupInformation.getLoginUser)
        } else {
          UserGroupInformation.createRemoteUser(realUser)
        }
      }

      if (!proxyUser.equalsIgnoreCase(realUser)) {
        ProxyUsers.refreshSuperUserGroupsConfiguration(hadoopConf)
        ProxyUsers.authorize(UserGroupInformation.createProxyUser(proxyUser, sessionUgi), ipAddress)
      }
    } catch {
      case e: IOException =>
        throw KyuubiSQLException(
          "Failed to validate proxy privilege of " + realUser + " for " + proxyUser, e)
    }
  }
}
