/*
 * Copyright 2026 Moderne, Inc.
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

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.orca.clouddriver.OortService
import com.netflix.spinnaker.orca.pipeline.model.PipelineExecutionImpl
import com.netflix.spinnaker.orca.pipeline.model.StageExecutionImpl
import retrofit2.mock.Calls
import spock.lang.Specification
import spock.lang.Subject

class AzureImageFinderSpec extends Specification {

  def objectMapper = new ObjectMapper()
  def oortService = Mock(OortService)

  @Subject
  def azureImageFinder = new AzureImageFinder(objectMapper: objectMapper, oortService: oortService)

  // Wire shape that AzureVMImageLookupController#buildGalleryAzureNamedImage emits
  // for Shared Image Gallery results: stable imageName (= imageDefinitionName),
  // version segregated, URI carries the full gallery resource path.
  private static Map galleryImageWireShape(
      String region, String version, Map<String, String> tags,
      String imageDefinitionName = "moderne-arm64-noble") {
    [
        imageName: imageDefinitionName,
        version  : version,
        region   : region,
        uri      : "/subscriptions/sub/resourceGroups/rg/providers/" +
                   "Microsoft.Compute/galleries/moderne/images/" +
                   "${imageDefinitionName}/versions/${version}".toString(),
        tags     : tags,
    ]
  }

  def "compareVersions orders semver numerically, not lexicographically"() {
    expect:
    Integer.signum(AzureImageFinder.compareVersions(a, b)) == expected

    where:
    a            | b            | expected
    "1.10.0"     | "1.9.0"      | 1   // numeric: 10 > 9
    "1.9.0"      | "1.10.0"     | -1
    "2026.5.10"  | "2026.5.9"   | 1
    "1.0.0"      | "1.0.0"      | 0
    "1.0"        | "1.0.0"      | 0   // missing components treated as 0
    "1.0.1"      | "1.0"        | 1
    null         | "1.0.0"      | -1
    "1.0.0"      | null         | 1
    null         | null         | 0
    ""           | "1.0.0"      | -1
    "1.0.0-rc1"  | "1.0.0-rc2"  | -1  // non-numeric falls back to lex
  }

  def "AzureManagedImage compareTo tiebreaks on version when imageDefinitionName ties"() {
    given: "two gallery image versions with the same imageDefinitionName"
    def older = new AzureImageFinder.AzureManagedImage(
        imageName: "moderne-arm64-noble",
        version: "2026.5.9",
        region: "westus")
    def newer = new AzureImageFinder.AzureManagedImage(
        imageName: "moderne-arm64-noble",
        version: "2026.5.10",
        region: "westus")

    expect: "the newer version sorts first (compareTo < 0)"
    newer.compareTo(older) < 0
    older.compareTo(newer) > 0
  }

  def "dedup-per-region loop picks the highest-version gallery image"() {
    given: "three gallery image versions of the same definition, in cache-arrival order"
    def images = [
        new AzureImageFinder.AzureManagedImage(
            imageName: "moderne-arm64-noble", version: "2026.5.8", region: "westus"),
        new AzureImageFinder.AzureManagedImage(
            imageName: "moderne-arm64-noble", version: "2026.5.10", region: "westus"),
        new AzureImageFinder.AzureManagedImage(
            imageName: "moderne-arm64-noble", version: "2026.5.9", region: "westus"),
    ]

    when: "applying the same selection logic as AzureImageFinder#byTags"
    def sorted = images.toSorted()
    def latest = [:]
    sorted.each { image ->
      def existing = latest[image.region]
      if (existing == null || image.compareTo(existing) < 0) {
        latest[image.region] = image
      }
    }

    then: "the newest version wins regardless of cache arrival order"
    latest["westus"].version == "2026.5.10"
  }

  def "byTags picks the highest gallery version when cache returns versions out of order"() {
    given: "a deploy stage targeting westus"
    def stage = new StageExecutionImpl(PipelineExecutionImpl.newPipeline("orca"), "", [
        account: "moderne-azure",
        regions: ["westus"],
    ])
    def baseTags = [moderne_base: "true", moderne_base_os: "ubuntu-arm64-24.04"]

    when:
    def imageDetails = azureImageFinder.byTags(
        stage, "moderne",
        [moderne_base: "true", moderne_base_os: "ubuntu-arm64-24.04"],
        [])

    then: "clouddriver is asked for gallery images only and returns three out-of-order versions"
    1 * oortService.findImage("azure", "moderne", "moderne-azure", null, [
        "tag:moderne_base"   : "true",
        "tag:moderne_base_os": "ubuntu-arm64-24.04",
        "managedImages"      : "false",
        "galleryImages"      : "true",
    ]) >> Calls.response([
        galleryImageWireShape("westus", "2026.5.8", baseTags),
        galleryImageWireShape("westus", "2026.5.10", baseTags),
        galleryImageWireShape("westus", "2026.5.9", baseTags),
    ])
    0 * _

    and: "byTags returns exactly the newest version's URI"
    imageDetails.size() == 1
    def selected = imageDetails.first()
    selected.region == "westus"
    selected.imageName == "moderne-arm64-noble"
    selected.imageId.endsWith("/versions/2026.5.10")
    selected.get("version") == "2026.5.10"
  }

  def "byTags filters out gallery images from regions the deploy doesn't target"() {
    given: "deploy targets westus; an extra gallery version lives in eastus"
    def stage = new StageExecutionImpl(PipelineExecutionImpl.newPipeline("orca"), "", [
        account: "moderne-azure",
        regions: ["westus"],
    ])
    def baseTags = [moderne_base: "true", moderne_base_os: "ubuntu-arm64-24.04"]

    when:
    def imageDetails = azureImageFinder.byTags(
        stage, "moderne",
        [moderne_base: "true", moderne_base_os: "ubuntu-arm64-24.04"],
        [])

    then: "clouddriver returns gallery images in both regions; only the targeted region survives"
    1 * oortService.findImage("azure", "moderne", "moderne-azure", null, _) >> Calls.response([
        galleryImageWireShape("eastus", "2026.5.10", baseTags),
        galleryImageWireShape("westus", "2026.5.10", baseTags),
    ])
    0 * _

    and:
    imageDetails.size() == 1
    imageDetails.first().region == "westus"
    imageDetails.first().imageId.endsWith("/versions/2026.5.10")
  }

  def "byTags picks the newest version per region when multiple regions are targeted"() {
    given:
    def stage = new StageExecutionImpl(PipelineExecutionImpl.newPipeline("orca"), "", [
        account: "moderne-azure",
        regions: ["westus", "eastus"],
    ])
    def baseTags = [moderne_base: "true", moderne_base_os: "ubuntu-arm64-24.04"]

    when:
    def imageDetails = azureImageFinder.byTags(
        stage, "moderne",
        [moderne_base: "true", moderne_base_os: "ubuntu-arm64-24.04"],
        [])

    then:
    1 * oortService.findImage("azure", "moderne", "moderne-azure", null, _) >> Calls.response([
        galleryImageWireShape("westus", "2026.5.8", baseTags),
        galleryImageWireShape("westus", "2026.5.10", baseTags),
        galleryImageWireShape("eastus", "2026.5.7", baseTags),
        galleryImageWireShape("eastus", "2026.5.9", baseTags),
    ])
    0 * _

    and:
    imageDetails.size() == 2
    imageDetails.find { it.region == "westus" }.imageId.endsWith("/versions/2026.5.10")
    imageDetails.find { it.region == "eastus" }.imageId.endsWith("/versions/2026.5.9")
  }

  def "byTags returns null when clouddriver has no matching images"() {
    given:
    def stage = new StageExecutionImpl(PipelineExecutionImpl.newPipeline("orca"), "", [
        account: "moderne-azure",
        regions: ["westus"],
    ])

    when:
    def imageDetails = azureImageFinder.byTags(stage, "moderne",
        [moderne_base: "true", moderne_base_os: "ubuntu-arm64-24.04"], [])

    then:
    1 * oortService.findImage("azure", "moderne", "moderne-azure", null, _) >> Calls.response([])
    0 * _

    and:
    imageDetails == null
  }

  def "byTags throws when regions are not specified"() {
    given:
    def stage = new StageExecutionImpl(PipelineExecutionImpl.newPipeline("orca"), "", [
        account: "moderne-azure",
        regions: [],
    ])

    when:
    azureImageFinder.byTags(stage, "moderne", [moderne_base: "true"], [])

    then:
    thrown(IllegalArgumentException)
    0 * oortService._
  }
}
