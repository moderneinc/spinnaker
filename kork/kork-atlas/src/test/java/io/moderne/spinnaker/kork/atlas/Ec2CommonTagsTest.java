package io.moderne.spinnaker.kork.atlas;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static String expectedHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
