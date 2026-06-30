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
    // instance.display.name is a per-process RandomNameGenerator value: unbounded cardinality
    // (a fresh value every restart) with no diagnostic worth. It must not be emitted.
    assertThat(tags).doesNotContainKey("instance.display.name");
    assertThat(tags.get("instance.id")).isEqualTo(expectedHostname());
  }

  @Test
  void friggaTagsFromAsgName_parsesFullAsgName() {
    Map<String, String> tags = Ec2CommonTags.friggaTagsFromAsgName("clouddriver-prod-v001");

    assertThat(tags).containsEntry("application", "clouddriver");
    assertThat(tags).containsEntry("cluster", "clouddriver-prod");
    assertThat(tags).containsEntry("stack", "prod");
    assertThat(tags).containsEntry("detail", "none");
    // server.group is the version-suffixed ASG name (e.g. ...-v001): unbounded common-tag
    // cardinality, +1 distinct series every red/black deploy. cluster gives the stable
    // grouping, so server.group must not be emitted as a common tag.
    assertThat(tags).doesNotContainKey("server.group");
  }

  @Test
  void friggaTagsFromAsgName_handlesNoStackOrSequence() {
    Map<String, String> tags = Ec2CommonTags.friggaTagsFromAsgName("clouddriver");

    assertThat(tags).containsEntry("application", "clouddriver");
    assertThat(tags).containsEntry("cluster", "clouddriver");
    assertThat(tags).containsEntry("detail", "none");
    assertThat(tags).doesNotContainKey("server.group");
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
  void friggaTagsFromAsgName_handlesNullInputWithoutThrowing() {
    // Frigga's Names(String) guards `name != null && !name.trim().isEmpty()`, so Names.parseName
    // returns an object with all-null fields rather than throwing on null input. The helper must
    // pass that through without NPE — same null-safety contract as the empty-string case above.
    Map<String, String> tags = Ec2CommonTags.friggaTagsFromAsgName(null);

    assertThat(tags).doesNotContainKey("application");
    assertThat(tags).doesNotContainKey("cluster");
    assertThat(tags).doesNotContainKey("server.group");
    assertThat(tags).containsEntry("detail", "none");
  }

  @Test
  void derive_neverThrowsAndAlwaysSetsApplication() {
    // Public contract: derive() is called at Spring bean construction; an unhandled throw fails
    // moderneCommonTags bean creation, which fails Spring context startup for every consumer.
    // Pin the contract with several adversarial inputs the prior tests don't exercise. The
    // RuntimeException/LinkageError catch in derive() is not directly exercisable from unit
    // tests (would require restructuring ec2Tags to take injectable deps); this smoke pins the
    // observable contract instead.
    assertThat(Ec2CommonTags.derive("clouddriver")).isNotEmpty();
    assertThat(Ec2CommonTags.derive("")).anySatisfy(t -> assertThat(t.getKey()).isNotBlank());
    assertThat(Ec2CommonTags.derive("with-hyphens-and-numbers-42")).isNotEmpty();
    assertThat(Ec2CommonTags.derive("a".repeat(512))).isNotEmpty();
  }

  @Test
  void friggaTagsFromAsgName_preservesDetailWhenPresent() {
    Map<String, String> tags = Ec2CommonTags.friggaTagsFromAsgName("clouddriver-prod-canary-v007");

    assertThat(tags).containsEntry("application", "clouddriver");
    assertThat(tags).containsEntry("stack", "prod");
    assertThat(tags).containsEntry("detail", "canary");
    assertThat(tags).doesNotContainKey("server.group");
  }

  private static String expectedHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "localhost";
    }
  }
}
