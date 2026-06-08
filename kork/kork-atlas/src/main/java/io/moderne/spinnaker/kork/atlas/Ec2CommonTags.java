package io.moderne.spinnaker.kork.atlas;

import com.netflix.frigga.Names;
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

    static Map<String, String> friggaTagsFromAsgName(String asgName) {
        Names names = Names.parseName(asgName);
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("application", names.getApp());
        tags.put("cluster", names.getCluster());
        if (names.getStack() != null && !names.getStack().isBlank()) {
            tags.put("stack", names.getStack());
        }
        String detail = names.getDetail();
        tags.put("detail", (detail != null && !detail.isBlank()) ? detail : "none");
        tags.put("server.group", names.getGroup());
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
