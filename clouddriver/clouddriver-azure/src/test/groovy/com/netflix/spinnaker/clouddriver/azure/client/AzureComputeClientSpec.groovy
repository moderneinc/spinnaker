package com.netflix.spinnaker.clouddriver.azure.client

import com.netflix.spinnaker.clouddriver.azure.resources.servergroup.model.AzureInstance
import com.netflix.spinnaker.clouddriver.model.HealthState
import spock.lang.Specification

class AzureComputeClientSpec extends Specification {

  private static AzureInstance instance(String name, String resourceId) {
    def vm = new AzureInstance()
    vm.name = name
    vm.resourceId = resourceId
    vm
  }

  void "resolveInstanceIds maps scale set VM names to instance indexes"() {
    given:
    def instances = [instance("myapp-dev-v086_0", "0"), instance("myapp-dev-v086_3", "3")]

    expect:
    AzureComputeClient.resolveInstanceIds(instances, ["myapp-dev-v086_3"]) == ["3"]
  }

  void "resolveInstanceIds preserves the order of the requested names"() {
    given:
    def instances = [instance("myapp-dev-v086_0", "0"), instance("myapp-dev-v086_3", "3")]

    expect:
    AzureComputeClient.resolveInstanceIds(instances, ["myapp-dev-v086_3", "myapp-dev-v086_0"]) == ["3", "0"]
  }

  void "resolveInstanceIds fails loudly when a name does not resolve"() {
    given:
    def instances = [instance("myapp-dev-v086_0", "0")]

    when:
    AzureComputeClient.resolveInstanceIds(instances, ["myapp-dev-v086_9"])

    then:
    def e = thrown(IllegalArgumentException)
    e.message.contains("myapp-dev-v086_9")
  }

  // The bare every() was vacuously true on an empty list, so the health gate passed instantly
  // during a scale-up from zero — the case it most needs to catch.
  void "allInstancesHealthy rejects an empty scale set"() {
    expect:
      !AzureComputeClient.allInstancesHealthy([])
      !AzureComputeClient.allInstancesHealthy(null)
  }

  void "allInstancesHealthy requires every instance to be Up"() {
    given:
      def up = instance("myapp-dev-v086_0", "0")
      up.healthState = HealthState.Up
      def down = instance("myapp-dev-v086_1", "1")
      down.healthState = HealthState.Down

    expect:
      AzureComputeClient.allInstancesHealthy([up])
      !AzureComputeClient.allInstancesHealthy([up, down])
  }

  void "resolveInstanceIds fails loudly when the server group has no instances"() {
    when:
    AzureComputeClient.resolveInstanceIds(null, ["myapp-dev-v086_0"])

    then:
    thrown(IllegalArgumentException)
  }
}
