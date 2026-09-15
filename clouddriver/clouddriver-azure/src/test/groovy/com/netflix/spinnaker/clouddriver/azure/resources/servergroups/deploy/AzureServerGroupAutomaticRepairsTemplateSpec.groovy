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

package com.netflix.spinnaker.clouddriver.azure.resources.servergroups.deploy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.clouddriver.azure.resources.loadbalancer.model.AzureLoadBalancer
import com.netflix.spinnaker.clouddriver.azure.resources.servergroup.model.AzureServerGroupDescription
import com.netflix.spinnaker.clouddriver.azure.resources.vmimage.model.AzureNamedImage
import com.netflix.spinnaker.clouddriver.azure.security.AzureCredentials
import com.netflix.spinnaker.clouddriver.azure.templates.AzureServerGroupResourceTemplate
import spock.lang.Specification

class AzureServerGroupAutomaticRepairsTemplateSpec extends Specification {

  def "health extension enables automatic repairs with the platform default grace period"() {
    given:
    def description = createDescription()
    description.healthSettings = healthSettings("http")

    when:
    JsonNode policy = scaleSetProperties(description).get("automaticRepairsPolicy")

    then:
    policy.get("enabled").asBoolean()
    !policy.has("gracePeriod")
    !policy.has("repairAction")
  }

  def "health check grace period is passed through in seconds"() {
    given:
    def description = createDescription()
    description.healthSettings = healthSettings("http")
    description.healthCheckGracePeriod = gracePeriodSeconds

    when:
    JsonNode policy = scaleSetProperties(description).get("automaticRepairsPolicy")

    then:
    policy.get("gracePeriod").asText() == expected

    where:
    gracePeriodSeconds || expected
    600                || "PT600S"
    300                || "PT300S"
    5401               || "PT5401S"
  }

  def "no automatic repairs policy without a health extension"() {
    given:
    def description = createDescription()
    description.healthSettings = settings
    description.healthCheckGracePeriod = 600

    expect:
    !scaleSetProperties(description).has("automaticRepairsPolicy")

    where:
    settings << [null, healthSettings(null), healthSettings("")]
  }

  private static JsonNode scaleSetProperties(AzureServerGroupDescription description) {
    JsonNode template = new ObjectMapper().readTree(AzureServerGroupResourceTemplate.getTemplate(description))
    template.get("resources").find { it.get("type").asText() == "Microsoft.Compute/virtualMachineScaleSets" }.get("properties")
  }

  private static AzureServerGroupDescription.AzureExtensionHealthSettings healthSettings(String protocol) {
    new AzureServerGroupDescription.AzureExtensionHealthSettings(protocol: protocol, port: "8080", requestPath: "/actuator/health")
  }

  private AzureServerGroupDescription createDescription() {
    def description = new AzureServerGroupDescription(
      name: "azureMASM-st1-d11",
      cloudProvider: "azure",
      application: "azureMASM",
      stack: "st1",
      detail: "d11",
      region: "westus",
      upgradePolicy: AzureServerGroupDescription.UpgradePolicy.Manual,
      image: new AzureNamedImage(sku: "22_04-lts", offer: "ubuntu", publisher: "Canonical", version: "latest"),
      sku: new AzureServerGroupDescription.AzureScaleSetSku(name: "Standard_A1", capacity: 2, tier: "Standard"),
      osConfig: new AzureServerGroupDescription.AzureOperatingSystemConfig(),
      loadBalancerName: "load-balancer-name",
      loadBalancerType: AzureLoadBalancer.AzureLoadBalancerType.AZURE_APPLICATION_GATEWAY.toString(),
      credentials: GroovyMock(AzureCredentials)
    )
    description.clusterName = description.getClusterName()
    description
  }
}
