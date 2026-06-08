package io.moderne.spinnaker.kork.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Ec2CommonTagsTest {

  @Test
  void fallbackTags_producesDevTagSetForLocalEnvironment() {
    Map<String, String> tags = Ec2CommonTags.fallbackTags("clouddriver");

    assertThat(tags).containsEntry("cloud.provider", "none");
    assertThat(tags).containsEntry("application", "clouddriver");
    assertThat(tags).containsEntry("environment", "local");
    assertThat(tags).containsKey("instance.id");
    assertThat(tags).containsKey("instance.display.name");
    assertThat(tags.get("instance.display.name")).isNotBlank();
    assertThat(tags.get("instance.id")).isEqualTo(expectedHostname());
  }

  @Test
  void friggaTagsFromAsgName_parsesFullAsgName() {
    Map<String, String> tags = Ec2CommonTags.friggaTagsFromAsgName("clouddriver-prod-v001");

    assertThat(tags).containsEntry("application", "clouddriver");
    assertThat(tags).containsEntry("cluster", "clouddriver-prod");
    assertThat(tags).containsEntry("stack", "prod");
    assertThat(tags).containsEntry("detail", "none");
    assertThat(tags).containsEntry("server.group", "clouddriver-prod-v001");
  }

  @Test
  void friggaTagsFromAsgName_handlesNoStackOrSequence() {
    Map<String, String> tags = Ec2CommonTags.friggaTagsFromAsgName("clouddriver");

    assertThat(tags).containsEntry("application", "clouddriver");
    assertThat(tags).containsEntry("cluster", "clouddriver");
    assertThat(tags).containsEntry("detail", "none");
    assertThat(tags).containsEntry("server.group", "clouddriver");
  }

  @Test
  void derive_offEc2ProducesFallbackTags() {
    io.micrometer.core.instrument.Tags tags = Ec2CommonTags.derive("clouddriver");

    // Tags is iterable — convert to map for assertions
    Map<String, String> asMap = new LinkedHashMap<>();
    tags.forEach(t -> asMap.put(t.getKey(), t.getValue()));

    assertThat(asMap).containsEntry("cloud.provider", "none");
    assertThat(asMap).containsEntry("application", "clouddriver");
    assertThat(asMap).containsEntry("environment", "local");
  }

  @Test
  void friggaTagsFromAsgName_omitsNullKeysEntirelyWhenFriggaCannotParse() {
    // Frigga's Names.parseName returns all-null fields for input that doesn't match
    // its NAME_PATTERN. The helper must NOT propagate nulls into the map — they'd blow up
    // Tag.of downstream and fail Spring context startup for every consumer service.
    Map<String, String> tags = Ec2CommonTags.friggaTagsFromAsgName("");

    assertThat(tags).doesNotContainKey("application");
    assertThat(tags).doesNotContainKey("cluster");
    assertThat(tags).doesNotContainKey("stack");
    assertThat(tags).doesNotContainKey("server.group");
    // detail still defaults to "none" so the Atlas tag set always carries one
    assertThat(tags).containsEntry("detail", "none");
  }

  @Test
  void friggaTagsFromAsgName_preservesDetailWhenPresent() {
    Map<String, String> tags = Ec2CommonTags.friggaTagsFromAsgName("clouddriver-prod-canary-v007");

    assertThat(tags).containsEntry("application", "clouddriver");
    assertThat(tags).containsEntry("stack", "prod");
    assertThat(tags).containsEntry("detail", "canary");
    assertThat(tags).containsEntry("server.group", "clouddriver-prod-canary-v007");
  }

  private static String expectedHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "localhost";
    }
  }
}
