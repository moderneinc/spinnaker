/*
 * Copyright 2016 The original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.azure.resources.servergroup.model

import com.azure.resourcemanager.compute.models.StatusLevelTypes
import com.azure.resourcemanager.compute.models.VirtualMachineScaleSetVM
import com.netflix.spinnaker.clouddriver.azure.AzureCloudProvider
import com.netflix.spinnaker.clouddriver.azure.common.AzureUtilities
import com.netflix.spinnaker.clouddriver.model.HealthState
import com.netflix.spinnaker.clouddriver.model.Instance
import groovy.transform.CompileStatic

@CompileStatic
class AzureInstance implements Instance, Serializable {
  public static final String APP_HEALTH_EXT_LINUX = "Microsoft.ManagedServices.ApplicationHealthLinux"
  public static final String APP_HEALTH_EXT_WINDOWS = "Microsoft.ManagedServices.ApplicationHealthWindows"
  public static final String APP_HEALTH_PROVIDER = "AzureAppHealth"
  private static final List<String> TERMINAL_PROVISIONING_STATES = [
    AzureUtilities.ProvisioningState.FAILED,
    AzureUtilities.ProvisioningState.CANCELED,
    AzureUtilities.ProvisioningState.DELETED,
    "Deleting"
  ]
  String name
  String resourceId
  String vhd
  Long launchTime
  String zone = 'N/A'
  String instanceType
  List<Map<String, Object>> health = []
  final String providerType = AzureCloudProvider.ID
  final String cloudProvider = AzureCloudProvider.ID

  static AzureInstance build(VirtualMachineScaleSetVM vm) {
    AzureInstance instance = new AzureInstance()
    instance.name = vm.name()
    instance.instanceType = vm.sku().name()
    instance.resourceId = vm.instanceId()
    instance.vhd = vm.storageProfile()?.osDisk()?.vhd()?.uri()

    // A scale set VM lives in at most one availability zone; surface it so Deck can
    // display zone placement the way it does for other providers. Regions without
    // zone support (or VMs not pinned to a zone) report no zones, so keep 'N/A'.
    instance.zone = vm.innerModel()?.zones()?.getAt(0) ?: 'N/A'

    HealthState powerState = null
    HealthState provisioningState = null

    vm.instanceView()?.statuses()?.each { status ->
      def codes = status.code()?.split('/')
      if (!codes || codes.length < 2) return
      switch (codes[0]) {
        case "ProvisioningState":
          if (codes[1].equalsIgnoreCase(AzureUtilities.ProvisioningState.SUCCEEDED)) {
            instance.launchTime = status.time()?.toInstant()?.toEpochMilli()
          } else {
            provisioningState = provisioningHealthState(codes[1])
          }
          break
        case "PowerState":
          powerState = powerHealthState(codes[1])
          break
        default:
          break
      }
    }

    HealthState applicationHealth = readApplicationHealth(vm)

    instance.health = [platformHealth(powerState, provisioningState)]
    if (applicationHealth) {
      instance.health << ([
        type : APP_HEALTH_PROVIDER,
        state: applicationHealth.toString()
      ] as Map<String, Object>)
    }

    instance
  }

  @Override
  HealthState getHealthState() {
    someUpRemainingUnknown(health) ? HealthState.Up :
      anyStarting(health) ? HealthState.Starting :
        anyDown(health) ? HealthState.Down :
          anyOutOfService(health) ? HealthState.OutOfService :
            HealthState.Unknown
  }

  private static boolean anyDown(List<Map<String, Object>> healthList) {
    healthList.any { it.get('state') == HealthState.Down.toString() }
  }

  private static boolean someUpRemainingUnknown(List<Map<String, Object>> healthList) {
    List<Map<String, Object>> knownHealthList =
      healthList.findAll { it.get('state') != HealthState.Unknown.toString() }
    knownHealthList ? knownHealthList.every { it.get('state') == HealthState.Up.toString() } : false
  }

  private static boolean anyStarting(List<Map<String, Object>> healthList) {
    healthList.any { it.get('state') == HealthState.Starting.toString() }
  }

  private static boolean anyOutOfService(List<Map<String, Object>> healthList) {
    healthList.any { it.get('state') == HealthState.OutOfService.toString() }
  }

  private static HealthState powerHealthState(String code) {
    if (code.equalsIgnoreCase("running")) {
      return HealthState.Unknown
    }
    if (code.equalsIgnoreCase("starting")) {
      return HealthState.Starting
    }
    return HealthState.Down
  }

  private static HealthState provisioningHealthState(String code) {
    TERMINAL_PROVISIONING_STATES.any { it.equalsIgnoreCase(code) } ? HealthState.Down : HealthState.Starting
  }

  private static Map<String, Object> platformHealth(HealthState powerState, HealthState provisioningState) {
    HealthState state = provisioningState == HealthState.Down
      ? HealthState.Down
      : (powerState ?: provisioningState ?: HealthState.Unknown)

    return [
      type       : 'Azure',
      healthClass: 'platform',
      state      : state.toString()
    ] as Map<String, Object>
  }

  private static HealthState readApplicationHealth(VirtualMachineScaleSetVM vm) {
    HealthState applicationHealth = null
    vm?.instanceView()?.extensions()?.each { extension ->
      if (extension.type() == APP_HEALTH_EXT_LINUX || extension.type() == APP_HEALTH_EXT_WINDOWS) {
        def substatuses = extension.substatuses()
        if (substatuses) {
          applicationHealth =
            substatuses[0]?.level() == StatusLevelTypes.ERROR ? HealthState.Down : HealthState.Up
        }
      }
    }
    applicationHealth
  }
}
