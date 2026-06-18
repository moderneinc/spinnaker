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
import org.mockito.Mockito
import spock.lang.Specification

abstract class AzureClientSpecBase extends Specification {

  protected static ManagementException managementExceptionWithStatus(int statusCode) {
    def httpResponse = Mockito.mock(HttpResponse)
    Mockito.when(httpResponse.getStatusCode()).thenReturn(statusCode)
    new ManagementException("Simulated ${statusCode}", httpResponse)
  }

  protected static sun.misc.Unsafe getUnsafe() {
    def f = sun.misc.Unsafe.getDeclaredField("theUnsafe")
    f.accessible = true
    f.get(null) as sun.misc.Unsafe
  }
}
