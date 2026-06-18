package com.netflix.spinnaker.clouddriver.azure.client

import com.azure.core.http.HttpResponse
import com.azure.core.management.exception.ManagementException
import com.azure.resourcemanager.compute.models.VirtualMachineScaleSet
import com.netflix.spinnaker.clouddriver.azure.resources.servergroup.model.AzureInstance
import org.mockito.MockedStatic
import org.mockito.Mockito
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

  void "resolveInstanceIds fails loudly when the server group has no instances"() {
    when:
    AzureComputeClient.resolveInstanceIds(null, ["myapp-dev-v086_0"])

    then:
    thrown(IllegalArgumentException)
  }

  private static ManagementException managementExceptionWithStatus(int statusCode) {
    def httpResponse = Mockito.mock(HttpResponse)
    Mockito.when(httpResponse.getStatusCode()).thenReturn(statusCode)
    new ManagementException("Simulated ${statusCode}", httpResponse)
  }

  private static AzureComputeClient allocateClient() {
    def f = sun.misc.Unsafe.getDeclaredField("theUnsafe")
    f.accessible = true
    def unsafe = f.get(null) as sun.misc.Unsafe
    unsafe.allocateInstance(AzureComputeClient) as AzureComputeClient
  }

  def 'resizeServerGroup calls executeOp for both get and apply when server group exists'() {
    given:
    def client = allocateClient()
    // @CompileStatic in AzureComputeClient enforces the VirtualMachineScaleSet return type,
    // so the mock must be typed accordingly even though executeOp closures are not executed.
    def mockVmss = Mockito.mock(VirtualMachineScaleSet)

    MockedStatic<AzureBaseClient> staticMock = Mockito.mockStatic(AzureBaseClient)
    // First call (getByResourceGroup) → return vmss; second call (apply) → return null (success)
    staticMock.when({ AzureBaseClient.executeOp(Mockito.any(Closure)) })
        .thenReturn(mockVmss)
        .thenReturn(null)
    staticMock.when({ AzureBaseClient.executeOp(Mockito.any(Closure), Mockito.anyLong()) })
        .thenReturn(mockVmss)
        .thenReturn(null)

    when:
    def result = client.resizeServerGroup("my-rg", "my-vmss", 0)

    then: 'executeOp is called twice — once for the get, once for the apply()'
    staticMock.verify({ AzureBaseClient.executeOp(Mockito.any(Closure)) }, Mockito.times(2))
    result == null

    cleanup:
    staticMock.close()
  }

  def 'resizeServerGroup skips apply when server group is not found'() {
    given:
    def client = allocateClient()

    MockedStatic<AzureBaseClient> staticMock = Mockito.mockStatic(AzureBaseClient)
    // Get returns null → server group not found; apply must not be called
    staticMock.when({ AzureBaseClient.executeOp(Mockito.any(Closure)) }).thenReturn(null)
    staticMock.when({ AzureBaseClient.executeOp(Mockito.any(Closure), Mockito.anyLong()) }).thenReturn(null)

    when:
    def result = client.resizeServerGroup("my-rg", "my-vmss", 0)

    then: 'only the get executeOp call is made; no NPE from calling update() on null'
    staticMock.verify({ AzureBaseClient.executeOp(Mockito.any(Closure)) }, Mockito.times(1))
    result == null
    noExceptionThrown()

    cleanup:
    staticMock.close()
  }
}
