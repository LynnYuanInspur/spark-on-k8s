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
package org.apache.spark.deploy.k8s.submit.submitsteps

import io.fabric8.kubernetes.api.model.ServiceBuilder
import scala.collection.JavaConverters._

import org.apache.spark.SparkConf
import org.apache.spark.deploy.k8s.config._
import org.apache.spark.deploy.k8s.constants._
import org.apache.spark.internal.Logging
import org.apache.spark.util.Clock

/**
 * Allows the driver to be reachable by executor pods through a headless service. The service's
 * ports should correspond to the ports that the executor will reach the pod at for RPC.
 */
private[spark] class DriverServiceBootstrapStep(
    kubernetesResourceNamePrefix: String,
    driverLabels: Map[String, String],
    submissionSparkConf: SparkConf,
    clock: Clock) extends DriverConfigurationStep with Logging {
  import DriverServiceBootstrapStep._

  override def configureDriver(driverSpec: KubernetesDriverSpec): KubernetesDriverSpec = {
    require(submissionSparkConf.getOption(DRIVER_BIND_ADDRESS_KEY).isEmpty,
      s"$DRIVER_BIND_ADDRESS_KEY is not supported in Kubernetes mode, as the driver's bind" +
        s" address is managed and set to the driver pod's IP address.")
    require(submissionSparkConf.getOption(DRIVER_HOST_KEY).isEmpty,
      s"$DRIVER_HOST_KEY is not supported in Kubernetes mode, as the driver's hostname will be" +
        s" managed via a Kubernetes service.")

    val preferredServiceName = s"$kubernetesResourceNamePrefix$DRIVER_SVC_POSTFIX"
    val resolvedServiceName = if (preferredServiceName.length <= MAX_SERVICE_NAME_LENGTH) {
      preferredServiceName
    } else {
      val randomServiceId = clock.getTimeMillis()
      val shorterServiceName = s"spark-$randomServiceId$DRIVER_SVC_POSTFIX"
      logWarning(s"Driver's hostname would preferably be $preferredServiceName, but this is" +
        s" too long (must be <= 63 characters). Falling back to use $shorterServiceName" +
        s" as the driver service's name.")
      shorterServiceName
    }

    val driverPort = submissionSparkConf.getInt("spark.driver.port", DEFAULT_DRIVER_PORT)
    val driverBlockManagerPort = submissionSparkConf.getInt(
        org.apache.spark.internal.config.DRIVER_BLOCK_MANAGER_PORT.key, DEFAULT_BLOCKMANAGER_PORT)
    val driverService = new ServiceBuilder()
      .withNewMetadata()
        .withName(resolvedServiceName)
        .endMetadata()
      .withNewSpec()
        .withClusterIP("None")
        .withSelector(driverLabels.asJava)
        .addNewPort()
          .withName(DRIVER_PORT_NAME)
          .withPort(driverPort)
          .withNewTargetPort(driverPort)
          .endPort()
        .addNewPort()
          .withName(BLOCK_MANAGER_PORT_NAME)
          .withPort(driverBlockManagerPort)
          .withNewTargetPort(driverBlockManagerPort)
          .endPort()
        .endSpec()
      .build()

    val uiPort = submissionSparkConf.getInt("spark.ui.port", DEFAULT_UI_PORT)
    val nodePort = submissionSparkConf.getInt("spark.kubernetes.driver.nodeport", 0)
    val driver_ui_service = resolvedServiceName + "-ui"
    val driverService_ui = new ServiceBuilder()
      .withNewMetadata()
        .withName(driver_ui_service)
        .endMetadata()
      .withNewSpec()
        .withType("NodePort")
        .withSelector(driverLabels.asJava)
        .addNewPort()
          .withName("driver-ui-port")
          .withPort(uiPort)
          .withNodePort(nodePort)
          .endPort()
        .endSpec()
      .build()

    val namespace = submissionSparkConf.get(KUBERNETES_NAMESPACE)
    val domain = submissionSparkConf.get(
      "spark.kubernetes.svc.domain",
      "svc.cluster.local")
    val driverHostname = s"${driverService.getMetadata.getName}.$namespace.$domain"
    val resolvedSparkConf = driverSpec.driverSparkConf.clone()
        .set(org.apache.spark.internal.config.DRIVER_HOST_ADDRESS, driverHostname)
        .set("spark.driver.port", driverPort.toString)
        .set(
          org.apache.spark.internal.config.DRIVER_BLOCK_MANAGER_PORT, driverBlockManagerPort)

    driverSpec.copy(
      driverSparkConf = resolvedSparkConf,
      otherKubernetesResources = driverSpec.otherKubernetesResources
        ++ Seq(driverService, driverService_ui)
    )
  }
}

private[spark] object DriverServiceBootstrapStep {
  val DRIVER_BIND_ADDRESS_KEY = org.apache.spark.internal.config.DRIVER_BIND_ADDRESS.key
  val DRIVER_HOST_KEY = org.apache.spark.internal.config.DRIVER_HOST_ADDRESS.key
  val DRIVER_SVC_POSTFIX = "-driver-svc"
  val MAX_SERVICE_NAME_LENGTH = 59 // 原63，减去-ui的长度
}
