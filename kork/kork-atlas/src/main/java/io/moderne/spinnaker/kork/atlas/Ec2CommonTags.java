package io.moderne.spinnaker.kork.atlas;

import org.kohsuke.randname.RandomNameGenerator;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Ec2CommonTags {

    private Ec2CommonTags() {}

    static Map<String, String> fallbackTags(String applicationName) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("cloud.provider", "none");
        tags.put("application", applicationName);
        tags.put("environment", "local");
        tags.put("instance.id", hostname());
        tags.put("instance.display.name", new RandomNameGenerator().next());
        return tags;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
