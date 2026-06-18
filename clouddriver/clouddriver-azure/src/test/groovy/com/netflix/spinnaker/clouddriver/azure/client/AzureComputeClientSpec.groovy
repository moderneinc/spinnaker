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

package com.netflix.spinnaker.clouddriver.azure.client

import com.azure.resourcemanager.AzureResourceManager
import com.azure.resourcemanager.compute.models.VirtualMachineScaleSet
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
