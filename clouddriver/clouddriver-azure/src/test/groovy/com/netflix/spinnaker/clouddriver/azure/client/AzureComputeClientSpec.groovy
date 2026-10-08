package com.netflix.spinnaker.clouddriver.azure.client

import com.azure.resourcemanager.AzureResourceManager
import com.azure.resourcemanager.compute.models.VirtualMachineScaleSet
import com.netflix.spinnaker.clouddriver.azure.resources.servergroup.model.AzureInstance
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.stubbing.Answer

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for AzureComputeClient.resizeServerGroup.
 *
 * AzureComputeClient requires real Azure credentials at construction time, so we
 * allocate an instance via Unsafe (bypassing the constructor).
 *
 * Integration-level tests that exercise real executeOp retry logic inject a mocked
 * AzureResourceManager directly into the azure field via Unsafe.
 *
 * Structural tests that need to verify the number of executeOp invocations mock
 * AzureBaseClient.executeOp statically.
 */
class AzureComputeClientSpec extends AzureClientSpecBase {

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

  private static AzureComputeClient allocateClient() {
    getUnsafe().allocateInstance(AzureComputeClient) as AzureComputeClient
  }

  private static void injectAzureField(AzureComputeClient client, AzureResourceManager azure) {
    def azureField = AzureBaseClient.getDeclaredField("azure")
    getUnsafe().putObject(client, getUnsafe().objectFieldOffset(azureField), azure)
  }

  // ---------------------------------------------------------------------------
  // Integration tests — real executeOp, mocked Azure SDK
  // ---------------------------------------------------------------------------

  def 'resizeServerGroup re-fetches VMSS from Azure on each retry when apply throws 409'() {
    given: 'a client whose azure field points at a mocked AzureResourceManager'
    def client = allocateClient()
    def conflictEx = managementExceptionWithStatus(HttpURLConnection.HTTP_CONFLICT)
    def getCallCount = new AtomicInteger(0)

    def mockVmss = Mockito.mock(VirtualMachineScaleSet, Mockito.RETURNS_DEEP_STUBS)
    Mockito.when(mockVmss.update().withCapacity(Mockito.anyInt()).apply())
        .thenThrow(conflictEx)
        .thenReturn(mockVmss)

    def mockAzure = Mockito.mock(AzureResourceManager, Mockito.RETURNS_DEEP_STUBS)
    Mockito.when(mockAzure.virtualMachineScaleSets()
        .getByResourceGroup(Mockito.anyString(), Mockito.anyString()))
        .thenAnswer({ inv -> getCallCount.incrementAndGet(); mockVmss } as Answer)

    injectAzureField(client, mockAzure)

    when:
    client.resizeServerGroup("my-rg", "my-vmss", 0)

    then: 'the VMSS was re-fetched on the second attempt, proving get lives inside the retry closure'
    getCallCount.get() == 2
    noExceptionThrown()
  }

  def 'resizeServerGroup completes without exception when no server group is found'() {
    given:
    def client = allocateClient()

    def mockAzure = Mockito.mock(AzureResourceManager, Mockito.RETURNS_DEEP_STUBS)
    Mockito.when(mockAzure.virtualMachineScaleSets()
        .getByResourceGroup(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(null)

    injectAzureField(client, mockAzure)

    when:
    def result = client.resizeServerGroup("my-rg", "my-vmss", 0)

    then: 'null VMSS is handled gracefully — no NPE from update() on null'
    result == null
    noExceptionThrown()
  }

  // ---------------------------------------------------------------------------
  // Structural tests — executeOp mocked statically
  // ---------------------------------------------------------------------------

  def 'resizeServerGroup wraps the entire get-and-apply sequence in one executeOp call'() {
    given:
    def client = allocateClient()

    MockedStatic<AzureBaseClient> staticMock = Mockito.mockStatic(AzureBaseClient)
    staticMock.when({ AzureBaseClient.executeOp(Mockito.any(Closure)) }).thenReturn(null)
    staticMock.when({ AzureBaseClient.executeOp(Mockito.any(Closure), Mockito.anyLong()) }).thenReturn(null)

    when:
    def result = client.resizeServerGroup("my-rg", "my-vmss", 0)

    then: 'exactly one executeOp call — get and apply share the same retryable closure'
    staticMock.verify({ AzureBaseClient.executeOp(Mockito.any(Closure)) }, Mockito.times(1))
    result == null

    cleanup:
    staticMock.close()
  }
}
