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
import spock.lang.Unroll

class AzureImageFinderSpec extends Specification {

  def objectMapper = new ObjectMapper()
  def oortService = Mock(OortService)

  @Subject
  def azureImageFinder = new AzureImageFinder(objectMapper: objectMapper, oortService: oortService)

  // Wire shape that AzureVMImageLookupController#buildGalleryAzureNamedImage emits
  // for Shared Image Gallery results: stable imageName (= imageDefinitionName),
  // version segregated, URI carries the full gallery resource path. (Field names
  // here match the finder's AzureManagedImage POJO so a stock ObjectMapper can
  // round-trip them in the test -- the controller-side wire field names that
  // don't map cleanly, like `ostype`, are dropped in production by Jackson
  // configuration and aren't relevant to selection.)
  private static Map galleryImageWireShape(String region, String version, Map<String, String> tags) {
    [
        imageName: "moderne-arm64-noble",
        version  : version,
        region   : region,
        uri      : "/subscriptions/sub/resourceGroups/rg/providers/" +
                   "Microsoft.Compute/galleries/moderne/images/" +
                   "moderne-arm64-noble/versions/${version}".toString(),
        tags     : tags,
    ]
  }

  // Wire shape for managed-image results: timestamp baked into imageName, no
  // version field.
  private static Map managedImageWireShape(String region, String name, Map<String, String> tags) {
    [
        imageName: name,
        region   : region,
        uri      : "/subscriptions/sub/resourceGroups/rg/providers/" +
                   "Microsoft.Compute/images/${name}".toString(),
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

  def "AzureManagedImage compareTo tiebreaks on version when imageName ties (gallery case)"() {
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

  def "AzureManagedImage compareTo falls back to imageName when names differ"() {
    given: "two managed images named with epoch-ms timestamps; gallery version absent"
    def today = new AzureImageFinder.AzureManagedImage(
        imageName: "moderne-1746961200000-noble-arm64",
        region: "canadacentral")
    def yesterday = new AzureImageFinder.AzureManagedImage(
        imageName: "moderne-1746874800000-noble-arm64",
        region: "canadacentral")

    expect: "alphabetically (= numerically, same prefix) later name sorts first"
    today.compareTo(yesterday) < 0
    yesterday.compareTo(today) > 0
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

  def "byTags picks the highest gallery version when cache returns versions out of order (devaz scenario)"() {
    given: "a deploy stage targeting westus -- the devaz tenant scenario"
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

    then: "clouddriver returns three gallery versions of the same image definition, out of order"
    1 * oortService.findImage("azure", "moderne", "moderne-azure", null, [
        "tag:moderne_base"   : "true",
        "tag:moderne_base_os": "ubuntu-arm64-24.04",
        "managedImages"      : "true",
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

  def "byTags filters out images from regions the deploy doesn't target"() {
    given: "deploy targets westus; canadacentral is the bake region only"
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

    then: "clouddriver returns the fresh managed image (canadacentral) AND replicated gallery (westus)"
    1 * oortService.findImage("azure", "moderne", "moderne-azure", null, _) >> Calls.response([
        managedImageWireShape("canadacentral",
            "moderne-1746961200000-noble-arm64", baseTags),
        galleryImageWireShape("westus", "2026.5.10", baseTags),
    ])
    0 * _

    and: "only the westus gallery image is returned; the canadacentral managed image is region-filtered out"
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

  def "byTags exposes the documented gap when imageSource defaults to both"() {
    // With default `imageSource`, the controller still returns both kinds when
    // they coexist in a region. The comparator can't tell that the timestamped
    // managed name is newer than a stable gallery definitionName, because the
    // lexicographic compare picks the alphabetically-later string -- 'a' > '1',
    // so any "moderne-arm64-..." gallery name beats "moderne-<epoch>-..." even
    // when older. Pinning `imageSource: "gallery"` (next test) avoids this.
    given:
    def stage = new StageExecutionImpl(PipelineExecutionImpl.newPipeline("orca"), "", [
        account: "moderne-azure",
        regions: ["canadacentral"],
    ])
    def baseTags = [moderne_base: "true", moderne_base_os: "ubuntu-arm64-24.04"]

    when:
    def imageDetails = azureImageFinder.byTags(stage, "moderne",
        [moderne_base: "true", moderne_base_os: "ubuntu-arm64-24.04"], [])

    then:
    1 * oortService.findImage("azure", "moderne", "moderne-azure", null, _) >> Calls.response([
        managedImageWireShape("canadacentral",
            "moderne-1746961200000-noble-arm64", baseTags),
        galleryImageWireShape("canadacentral", "2026.5.1", baseTags),  // older!
    ])
    0 * _

    and: "gallery wins on name, not on version -- known limitation, structural fix is imageSource: gallery"
    imageDetails.size() == 1
    imageDetails.first().imageName == "moderne-arm64-noble"
    imageDetails.first().get("version") == "2026.5.1"
  }

  def "imageSource=gallery sends only galleryImages=true and structurally avoids the mixed-source gap"() {
    given: "the devaz-shape pipeline pins imageSource to gallery"
    def stage = new StageExecutionImpl(PipelineExecutionImpl.newPipeline("orca"), "", [
        account    : "moderne-azure",
        regions    : ["canadacentral"],
        imageSource: "gallery",
    ])
    def baseTags = [moderne_base: "true", moderne_base_os: "ubuntu-arm64-24.04"]

    when:
    def imageDetails = azureImageFinder.byTags(stage, "moderne",
        [moderne_base: "true", moderne_base_os: "ubuntu-arm64-24.04"], [])

    then: "controller is told to skip managed images entirely; only gallery rows come back"
    1 * oortService.findImage("azure", "moderne", "moderne-azure", null, [
        "tag:moderne_base"   : "true",
        "tag:moderne_base_os": "ubuntu-arm64-24.04",
        "managedImages"      : "false",
        "galleryImages"      : "true",
    ]) >> Calls.response([
        galleryImageWireShape("canadacentral", "2026.5.10", baseTags),
        galleryImageWireShape("canadacentral", "2026.5.8", baseTags),
    ])
    0 * _

    and: "the newest gallery version is selected -- no managed image was ever in the running"
    imageDetails.size() == 1
    imageDetails.first().get("version") == "2026.5.10"
  }

  def "imageSource=managed sends only managedImages=true (AWS-parity tenants)"() {
    given:
    def stage = new StageExecutionImpl(PipelineExecutionImpl.newPipeline("orca"), "", [
        account    : "moderne-azure",
        regions    : ["canadacentral"],
        imageSource: "managed",
    ])
    def baseTags = [moderne_base: "true", moderne_base_os: "ubuntu-arm64-24.04"]

    when:
    def imageDetails = azureImageFinder.byTags(stage, "moderne",
        [moderne_base: "true", moderne_base_os: "ubuntu-arm64-24.04"], [])

    then:
    1 * oortService.findImage("azure", "moderne", "moderne-azure", null, [
        "tag:moderne_base"   : "true",
        "tag:moderne_base_os": "ubuntu-arm64-24.04",
        "managedImages"      : "true",
        "galleryImages"      : "false",
    ]) >> Calls.response([
        managedImageWireShape("canadacentral",
            "moderne-1746874800000-noble-arm64", baseTags),
        managedImageWireShape("canadacentral",
            "moderne-1746961200000-noble-arm64", baseTags),
    ])
    0 * _

    and: "newest timestamp wins"
    imageDetails.size() == 1
    imageDetails.first().imageName == "moderne-1746961200000-noble-arm64"
  }

  def "imageSource is case-insensitive and accepts 'both' explicitly"() {
    given:
    def stage = new StageExecutionImpl(PipelineExecutionImpl.newPipeline("orca"), "", [
        account    : "moderne-azure",
        regions    : ["westus"],
        imageSource: source,
    ])

    when:
    azureImageFinder.byTags(stage, "moderne",
        [moderne_base: "true", moderne_base_os: "ubuntu-arm64-24.04"], [])

    then:
    1 * oortService.findImage("azure", "moderne", "moderne-azure", null, { Map m ->
      m["managedImages"] == expectedManaged && m["galleryImages"] == expectedGallery
    }) >> Calls.response([])

    where:
    source    | expectedManaged | expectedGallery
    "gallery" | "false"         | "true"
    "GALLERY" | "false"         | "true"
    "managed" | "true"          | "false"
    "Managed" | "true"          | "false"
    "both"    | "true"          | "true"
    "BOTH"    | "true"          | "true"
  }

  def "byTags throws when imageSource is unrecognised"() {
    given:
    def stage = new StageExecutionImpl(PipelineExecutionImpl.newPipeline("orca"), "", [
        account    : "moderne-azure",
        regions    : ["westus"],
        imageSource: "nonsense",
    ])

    when:
    azureImageFinder.byTags(stage, "moderne", [moderne_base: "true"], [])

    then:
    thrown(IllegalArgumentException)
    0 * oortService._
  }
}
