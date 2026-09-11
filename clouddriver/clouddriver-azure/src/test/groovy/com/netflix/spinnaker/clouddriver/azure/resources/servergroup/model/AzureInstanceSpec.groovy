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

import com.azure.resourcemanager.compute.fluent.models.VirtualMachineScaleSetVMInner
import com.azure.resourcemanager.compute.models.InstanceViewStatus
import com.azure.resourcemanager.compute.models.StatusLevelTypes
import com.azure.resourcemanager.compute.models.VirtualMachineExtensionInstanceView
import com.azure.resourcemanager.compute.models.Sku
import com.azure.resourcemanager.compute.models.VirtualMachineInstanceView
import com.azure.resourcemanager.compute.models.VirtualMachineScaleSetVM
import com.netflix.spinnaker.clouddriver.azure.common.AzureUtilities
import com.netflix.spinnaker.clouddriver.model.HealthState
import spock.lang.Specification
import spock.lang.Unroll

class AzureInstanceSpec extends Specification {

  def 'should generate a correctly structured instance'(){
    def vm = Mock(VirtualMachineScaleSetVM)
    def instanceView = Mock(VirtualMachineInstanceView)
    def sku = new Sku()

    def provisioningStatus = new InstanceViewStatus()
    provisioningStatus.withCode( 'ProvisioningState/' + AzureUtilities.ProvisioningState.SUCCEEDED)
    def powerStatus = new InstanceViewStatus()
    powerStatus.withCode( 'PowerState/Running')

    List<InstanceViewStatus> statuses = [provisioningStatus, powerStatus]


    vm.instanceView() >> instanceView
    vm.instanceView().statuses() >> statuses

    vm.sku() >> sku
    sku.name() >> "test"

    def instance = AzureInstance.build(vm)

    expect:
      instance.zone == 'N/A'
      instance.healthState == HealthState.Unknown
  }

  def 'should populate zone from the VMSS VM availability zone, falling back to N/A'(){
    def vm = Mock(VirtualMachineScaleSetVM)
    def sku = new Sku()

    vm.innerModel() >> innerWithZones(zones)
    vm.sku() >> sku
    sku.name() >> "test"

    expect:
      AzureInstance.build(vm).zone == expectedZone

    where:
      zones || expectedZone
      ['1'] || '1'
      null  || 'N/A'
      []    || 'N/A'
  }

  void "platform health reports Unknown for a running VM rather than claiming Up"() {
    given:
      def vm = vmWith(powerState: 'Running')

    when:
      def instance = AzureInstance.build(vm)

    then:
      instance.health.size() == 1
      instance.health[0].type == 'Azure'
      instance.health[0].healthClass == 'platform'
      instance.health[0].state == 'Unknown'

    and: "the aggregate degrades to Unknown too, as AmazonInstance does with no LB provider"
      instance.healthState == HealthState.Unknown
  }

  @Unroll
  void "platform health is #expectedState when the VM power state is #powerState"() {
    expect:
      AzureInstance.build(vmWith(powerState: powerState)).health[0].state == expectedState

    where:
      powerState     || expectedState
      'Running'      || 'Unknown'
      'starting'     || 'Starting'
      'Stopped'      || 'Down'
      'deallocating' || 'Down'
  }

  void "a booting VM reports Starting rather than Down"() {
    given:
      def vm = vmWith(provisioningState: 'Creating', powerState: 'starting')

    when:
      def instance = AzureInstance.build(vm)

    then:
      instance.health[0].state == 'Starting'
      instance.healthState == HealthState.Starting
  }

  @Unroll
  void "provisioning state #provisioningState with no power state reports #expectedState"() {
    expect:
      AzureInstance.build(vmWith(provisioningState: provisioningState)).health[0].state == expectedState

    where:
      provisioningState || expectedState
      'Creating'        || 'Starting'
      'Updating'        || 'Starting'
      'Failed'          || 'Down'
      'Canceled'        || 'Down'
      'Deleting'        || 'Down'
  }

  void "failed provisioning reports Down, which HealthHelper can act on, not Failed"() {
    given: "no PowerState status, so provisioning is the only platform signal"
      def vm = vmWith(provisioningState: 'failed')

    when:
      def instance = AzureInstance.build(vm)

    then:
      instance.health[0].state == 'Down'

    and: "the aggregate follows the list; Failed is gone, as it is on AWS"
      instance.healthState == HealthState.Down
  }

  @Unroll
  void "the application health extension is emitted as its own #providerType provider"() {
    given:
      def vm = vmWith(powerState: 'Running', extensionType: providerType, extensionLevel: level)

    when:
      def health = AzureInstance.build(vm).health

    then: "two providers: the platform's view and the application's"
      health.size() == 2
      health[0].type == 'Azure'
      health[0].state == 'Unknown'
      health[1].type == AzureInstance.APP_HEALTH_PROVIDER
      health[1].state == expectedState

    and: "app health is not classed as platform, so platformHealthOnly cannot pick it up"
      health[1].healthClass == null

    where:
      providerType                          | level                   || expectedState
      AzureInstance.APP_HEALTH_EXT_LINUX    | StatusLevelTypes.INFO   || 'Up'
      AzureInstance.APP_HEALTH_EXT_LINUX    | StatusLevelTypes.ERROR  || 'Down'
      AzureInstance.APP_HEALTH_EXT_WINDOWS  | StatusLevelTypes.INFO   || 'Up'
      AzureInstance.APP_HEALTH_EXT_WINDOWS  | StatusLevelTypes.ERROR  || 'Down'
  }

  void "a failing application health check leaves the platform entry untouched"() {
    given: "the VM is running but the app reports an error"
      def vm = vmWith(powerState: 'Running',
                      extensionType: AzureInstance.APP_HEALTH_EXT_LINUX,
                      extensionLevel: StatusLevelTypes.ERROR)

    when:
      def instance = AzureInstance.build(vm)

    then: "the two signals stay distinguishable, unlike the merged entry they replaced"
      instance.health[0].state == 'Unknown'
      instance.health[1].state == 'Down'
      instance.healthState == HealthState.Down
  }

  @Unroll
  void "the aggregate healthState is #expected when app health reports #appState"() {
    given:
      def vm = vmWith(powerState: 'Running',
                      extensionType: AzureInstance.APP_HEALTH_EXT_LINUX,
                      extensionLevel: appState)

    expect: "Unknown platform health is dropped before aggregating, per AmazonInstance"
      AzureInstance.build(vm).healthState == expected

    where:
      appState                || expected
      StatusLevelTypes.INFO   || HealthState.Up
      StatusLevelTypes.ERROR  || HealthState.Down
  }

  void "an extension reporting no substatuses yields no application provider"() {
    given:
      def vm = vmWith(powerState: 'Running', extensionType: AzureInstance.APP_HEALTH_EXT_LINUX)

    expect:
      AzureInstance.build(vm).health.size() == 1
  }

  private VirtualMachineScaleSetVM vmWith(Map args) {
    def vm = Mock(VirtualMachineScaleSetVM)
    def instanceView = Mock(VirtualMachineInstanceView)
    def sku = new Sku()

    List<InstanceViewStatus> statuses = []
    if (args.provisioningState) {
      statuses << new InstanceViewStatus().withCode('ProvisioningState/' + args.provisioningState)
    }
    if (args.powerState) {
      statuses << new InstanceViewStatus().withCode('PowerState/' + args.powerState)
    }

    List<VirtualMachineExtensionInstanceView> extensions = []
    if (args.extensionType) {
      def extension = new VirtualMachineExtensionInstanceView().withType(args.extensionType as String)
      if (args.extensionLevel) {
        extension.withSubstatuses([new InstanceViewStatus().withLevel(args.extensionLevel as StatusLevelTypes)])
      }
      extensions << extension
    }

    vm.instanceView() >> instanceView
    instanceView.statuses() >> statuses
    instanceView.extensions() >> extensions
    vm.innerModel() >> innerWithZones(null)
    vm.sku() >> sku
    sku.name() >> "test"
    vm
  }

  private static VirtualMachineScaleSetVMInner innerWithZones(List<String> zones) {
    def inner = new VirtualMachineScaleSetVMInner()
    def field = VirtualMachineScaleSetVMInner.getDeclaredField('zones')
    field.accessible = true
    field.set(inner, zones)
    inner
  }
}
