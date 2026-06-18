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

import com.azure.core.http.HttpResponse
import com.azure.core.management.exception.ManagementException
import com.azure.resourcemanager.compute.models.VirtualMachineScaleSet
import org.mockito.MockedStatic
import org.mockito.Mockito
import spock.lang.Specification

/**
 * Tests for AzureComputeClient.resizeServerGroup.
 *
 * AzureComputeClient requires real Azure credentials at construction time, so we
 * allocate an instance via Unsafe (bypassing the constructor) and mock the static
 * AzureBaseClient.executeOp to control Azure SDK interactions.
 */
class AzureComputeClientSpec extends Specification {

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
