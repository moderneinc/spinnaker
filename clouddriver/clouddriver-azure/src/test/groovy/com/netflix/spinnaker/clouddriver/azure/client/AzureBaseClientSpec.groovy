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

import com.azure.core.management.exception.ManagementException
import spock.lang.Unroll

/**
 * Tests for AzureBaseClient.executeOp retry logic, specifically that 409
 * ConflictingConcurrentWriteNotAllowed is treated as a transient error and retried.
 *
 * executeOp is a static method so no instance allocation is needed.
 */
class AzureBaseClientSpec extends AzureClientSpecBase {

  @Unroll
  def 'executeOp retries and succeeds after a transient #label'() {
    given:
    int callCount = 0
    def ex = managementExceptionWithStatus(statusCode)

    when:
    def result = AzureBaseClient.executeOp({
      if (++callCount == 1) throw ex
      return "success"
    }, 2L)

    then:
    result == "success"
    callCount == 2

    where:
    statusCode                              | label
    HttpURLConnection.HTTP_CONFLICT         | "409 Conflict (concurrent write)"
    HttpURLConnection.HTTP_CLIENT_TIMEOUT   | "408 Request Timeout"
    HttpURLConnection.HTTP_INTERNAL_ERROR   | "500 Internal Server Error"
    HttpURLConnection.HTTP_UNAVAILABLE      | "503 Service Unavailable"
    HttpURLConnection.HTTP_GATEWAY_TIMEOUT  | "504 Gateway Timeout"
  }

  def 'executeOp returns null without retrying on 404 Not Found'() {
    given:
    int callCount = 0
    def ex = managementExceptionWithStatus(HttpURLConnection.HTTP_NOT_FOUND)

    when:
    def result = AzureBaseClient.executeOp({
      callCount++
      throw ex
    }, 3L)

    then: '404 is not retried — resource is gone, retrying cannot help'
    result == null
    callCount == 1
  }

  def 'executeOp throws after exhausting all retries on 409'() {
    given:
    def ex = managementExceptionWithStatus(HttpURLConnection.HTTP_CONFLICT)

    when:
    AzureBaseClient.executeOp({ throw ex }, 2L)

    then:
    def thrown = thrown(ManagementException)
    thrown.is(ex)
  }
}
