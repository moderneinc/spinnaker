/*
 * Copyright 2026 The original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.azure.resources.servergroups.deploy.ops

import com.netflix.spinnaker.clouddriver.azure.client.AzureComputeClient
import com.netflix.spinnaker.clouddriver.azure.client.AzureNetworkClient
import com.netflix.spinnaker.clouddriver.azure.resources.loadbalancer.model.AzureLoadBalancer
import com.netflix.spinnaker.clouddriver.azure.resources.servergroup.model.AzureServerGroupDescription
import com.netflix.spinnaker.clouddriver.azure.resources.servergroup.model.EnableDisableDestroyAzureServerGroupDescription
import com.netflix.spinnaker.clouddriver.azure.resources.servergroup.ops.DisableAzureServerGroupAtomicOperation
import com.netflix.spinnaker.clouddriver.azure.security.AzureCredentials
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperationException
import spock.lang.Specification

class DisableAzureServerGroupAtomicOperationUnitSpec extends Specification {

  static final SERVER_GROUP_NAME = "testazure-web1-d1-v000"
  static final REGION = "westus"
  static final APP_NAME = "testazure"
  static final RESOURCE_GROUP = "${APP_NAME}-${REGION}"

  AzureComputeClient computeClient
  AzureNetworkClient networkClient
  EnableDisableDestroyAzureServerGroupDescription description

  def setup() {
    TaskRepository.threadLocalTask.set(Mock(Task))

    computeClient = GroovyMock(AzureComputeClient)
    networkClient = GroovyMock(AzureNetworkClient)
    def credentials = GroovyMock(AzureCredentials) {
      asBoolean() >> true
      getComputeClient() >> computeClient
      getNetworkClient() >> networkClient
    }

    description = new EnableDisableDestroyAzureServerGroupDescription(
      name: SERVER_GROUP_NAME,
      serverGroupName: SERVER_GROUP_NAME,
      region: REGION,
      application: APP_NAME,
      credentials: credentials
    )
  }

  void "suspends automatic repairs after disabling a server group that has them enabled"() {
    given:
    computeClient.getServerGroup(RESOURCE_GROUP, SERVER_GROUP_NAME) >> appGatewayServerGroup(disabled: false, automaticRepairsEnabled: true)

    when:
    new DisableAzureServerGroupAtomicOperation(description).operate([])

    then:
    1 * networkClient.disableServerGroup(RESOURCE_GROUP, RESOURCE_GROUP, "app-gateway", SERVER_GROUP_NAME, "backend-pool")

    then:
    1 * computeClient.suspendAutomaticRepairs(RESOURCE_GROUP, SERVER_GROUP_NAME)
  }

  void "suspends automatic repairs when the server group is already disabled"() {
    given:
    computeClient.getServerGroup(RESOURCE_GROUP, SERVER_GROUP_NAME) >> appGatewayServerGroup(disabled: true, automaticRepairsEnabled: true)

    when:
    new DisableAzureServerGroupAtomicOperation(description).operate([])

    then:
    0 * networkClient.disableServerGroup(*_)
    1 * computeClient.suspendAutomaticRepairs(RESOURCE_GROUP, SERVER_GROUP_NAME)
  }

  void "suspends automatic repairs after scaling down a server group without a load balancer"() {
    given:
    computeClient.getServerGroup(RESOURCE_GROUP, SERVER_GROUP_NAME) >> new AzureServerGroupDescription(
      name: SERVER_GROUP_NAME, disabled: false, automaticRepairsEnabled: true)

    when:
    new DisableAzureServerGroupAtomicOperation(description).operate([])

    then:
    1 * computeClient.resizeServerGroup(RESOURCE_GROUP, SERVER_GROUP_NAME, 0)

    then:
    1 * computeClient.suspendAutomaticRepairs(RESOURCE_GROUP, SERVER_GROUP_NAME)
  }

  void "does not touch automatic repairs when the server group does not have them enabled"() {
    given:
    computeClient.getServerGroup(RESOURCE_GROUP, SERVER_GROUP_NAME) >> appGatewayServerGroup(disabled: false, automaticRepairsEnabled: false)

    when:
    new DisableAzureServerGroupAtomicOperation(description).operate([])

    then:
    1 * networkClient.disableServerGroup(*_)
    0 * computeClient.suspendAutomaticRepairs(*_)
  }

  void "does not suspend automatic repairs when disabling fails"() {
    given:
    computeClient.getServerGroup(RESOURCE_GROUP, SERVER_GROUP_NAME) >> appGatewayServerGroup(disabled: false, automaticRepairsEnabled: true)
    networkClient.disableServerGroup(*_) >> { throw new RuntimeException("Azure API error") }

    when:
    new DisableAzureServerGroupAtomicOperation(description).operate([])

    then:
    thrown(AtomicOperationException)
    0 * computeClient.suspendAutomaticRepairs(*_)
  }

  void "fails the operation when suspending automatic repairs fails"() {
    given:
    computeClient.getServerGroup(RESOURCE_GROUP, SERVER_GROUP_NAME) >> appGatewayServerGroup(disabled: false, automaticRepairsEnabled: true)
    computeClient.suspendAutomaticRepairs(*_) >> { throw new RuntimeException("Azure API error") }

    when:
    new DisableAzureServerGroupAtomicOperation(description).operate([])

    then:
    thrown(AtomicOperationException)
  }

  private static AzureServerGroupDescription appGatewayServerGroup(Map properties) {
    new AzureServerGroupDescription(
      name: SERVER_GROUP_NAME,
      loadBalancerType: AzureLoadBalancer.AzureLoadBalancerType.AZURE_APPLICATION_GATEWAY.toString(),
      appGatewayName: "app-gateway",
      backendPoolName: "backend-pool",
      disabled: properties.disabled,
      automaticRepairsEnabled: properties.automaticRepairsEnabled
    )
  }
}
