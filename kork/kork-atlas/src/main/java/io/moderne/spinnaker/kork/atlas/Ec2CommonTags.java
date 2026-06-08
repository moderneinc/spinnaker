package io.moderne.spinnaker.kork.atlas;

import com.netflix.frigga.Names;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.kohsuke.randname.RandomNameGenerator;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

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

    public static Tags derive(String applicationName) {
        Map<String, String> map = imdsReachable() ? ec2Tags(applicationName) : fallbackTags(applicationName);
        return Tags.of(map.entrySet().stream()
                .map(e -> Tag.of(e.getKey(), e.getValue()))
                .collect(Collectors.toList()));
    }

    private static boolean imdsReachable() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(500))
                    .build();
            HttpRequest tokenRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://169.254.169.254/latest/api/token"))
                    .timeout(Duration.ofMillis(500))
                    .header("X-aws-ec2-metadata-token-ttl-seconds", "21600")
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = client.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    static Map<String, String> ec2Tags(String applicationName) {
        // Implemented in Task 5
        throw new UnsupportedOperationException("ec2Tags() not yet implemented");
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
