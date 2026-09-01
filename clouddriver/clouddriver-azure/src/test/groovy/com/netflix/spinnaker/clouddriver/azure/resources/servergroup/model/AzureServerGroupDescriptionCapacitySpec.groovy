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

package com.netflix.spinnaker.clouddriver.azure.resources.servergroup.model

import spock.lang.Specification

class AzureServerGroupDescriptionCapacitySpec extends Specification {

  private static AzureInstance instance(String name) {
    def i = new AzureInstance()
    i.name = name
    i
  }

  // An emptied server group previously reported a desired of 1, because an empty list is falsy
  // in Groovy, which surfaced as a wrong current size in the resize modal.
  void "an emptied server group reports zero rather than one"() {
    given:
      def description = new AzureServerGroupDescription()
      description.instances = [] as Set

    expect:
      description.capacity.desired == 0
      description.capacity.max == 0
      description.capacity.min == 0
  }

  void "capacity falls back to the observed instance count when sku is absent"() {
    given:
      def description = new AzureServerGroupDescription()
      description.instances = [instance("myapp-dev-v086_0"), instance("myapp-dev-v086_1")] as Set

    expect:
      description.capacity.desired == 2
  }

  // sku.capacity is what the scale set is configured for; instances only reflect what has been
  // observed, so a scale-up in progress should report the target rather than the partial count.
  void "capacity prefers the scale set's configured sku capacity"() {
    given:
      def description = new AzureServerGroupDescription()
      description.sku = new AzureServerGroupDescription.AzureScaleSetSku(capacity: 5)
      description.instances = [instance("myapp-dev-v086_0")] as Set

    expect:
      description.capacity.desired == 5
  }

  void "capacity is reported as pinned because a scale set has no autoscale range of its own"() {
    given:
      def description = new AzureServerGroupDescription()
      description.instances = [instance("myapp-dev-v086_0")] as Set

    expect:
      description.capacity.pinned
  }
}
