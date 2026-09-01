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

package com.netflix.spinnaker.orca.clouddriver.tasks.providers.azure

import spock.lang.Specification

class AzureServerGroupCreatorSpec extends Specification {

  // DetermineHealthProvidersTask builds healthProviderNamesByPlatform from this method. Returning
  // empty left interestingHealthProviderNames unset, so instance tasks fell back to ["Discovery"],
  // which Azure never reports, and their health waits never completed. The value must match the
  // type emitted by clouddriver's AzureInstance health provider entry.
  void "declares Azure as its platform health provider"() {
    expect:
      new AzureServerGroupCreator().healthProviderName == Optional.of("Azure")
  }

  void "is registered for the azure cloud provider"() {
    expect:
      new AzureServerGroupCreator().cloudProvider == "azure"
  }
}
