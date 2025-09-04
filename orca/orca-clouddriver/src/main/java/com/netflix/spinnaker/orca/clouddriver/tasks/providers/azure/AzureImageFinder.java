package com.netflix.spinnaker.orca.clouddriver.tasks.providers.azure;

import static java.util.stream.Collectors.toMap;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.frigga.ami.AppVersion;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.clouddriver.OortService;
import com.netflix.spinnaker.orca.clouddriver.tasks.image.ImageFinder;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AzureImageFinder implements ImageFinder {
  private static final Logger log = LoggerFactory.getLogger(AzureImageFinder.class);

  @Autowired OortService oortService;

  @Autowired ObjectMapper objectMapper;

  @Override
  public Collection<ImageDetails> byTags(
      StageExecution stage,
      String packageName,
      Map<String, String> tags,
      List<String> warningsCollector) {

    StageData stageData = (StageData) stage.mapTo(StageData.class);
    String account = stageData.account;
    List<String> regions = stageData.regions;

    if (regions == null || regions.isEmpty()) {
      throw new IllegalArgumentException("regions must be specified for Azure image search");
    }

    log.info(
        "Searching for Azure images with package: {} and tags: {} in regions: {} for account: {}",
        packageName,
        tags,
        regions,
        account);

    Map<String, String> searchParams = new HashMap<>(prefixTags(tags));
    searchParams.put("managedImages", "true");

    List<AzureManagedImage> allMatchedImages =
        oortService.findImage(getCloudProvider(), packageName, account, null, searchParams).stream()
            .map(image -> objectMapper.convertValue(image, AzureManagedImage.class))
            .filter(image -> regions.contains(image.region))
            .sorted()
            .collect(Collectors.toList());

    if (allMatchedImages.isEmpty()) {
      return null;
    }

    Map<String, AzureManagedImage> latestImagesByRegion = new HashMap<>();
    for (AzureManagedImage image : allMatchedImages) {
      String region = image.region;
      if (!latestImagesByRegion.containsKey(region)
          || image.compareTo(latestImagesByRegion.get(region)) < 0) {
        latestImagesByRegion.put(region, image);
      }
    }

    List<ImageDetails> imageDetailsList = new ArrayList<>();
    for (AzureManagedImage image : latestImagesByRegion.values()) {
      imageDetailsList.add(image.toAzureImageDetails());
    }

    return imageDetailsList;
  }

  @Override
  public String getCloudProvider() {
    return "azure";
  }

  static Map<String, String> prefixTags(Map<String, String> tags) {
    return tags.entrySet().stream()
        .collect(toMap(entry -> "tag:" + entry.getKey(), Map.Entry::getValue));
  }

  static class StageData {
    @JsonProperty String account;
    @JsonProperty List<String> regions;
    @JsonProperty String packageName;
    @JsonProperty Map<String, String> tags;
  }

  static class AzureManagedImage implements Comparable<AzureManagedImage> {
    @JsonProperty String imageName;
    @JsonProperty String resourceGroup;
    @JsonProperty String region;
    @JsonProperty String osType;
    @JsonProperty String uri;
    @JsonProperty Map<String, Object> attributes;
    @JsonProperty Map<String, String> tags;

    ImageDetails toAzureImageDetails() {
      AppVersion appVersion = AppVersion.parseName(tags.get("appversion"));
      JenkinsDetails jenkinsDetails =
          Optional.ofNullable(appVersion)
              .map(
                  av ->
                      new JenkinsDetails(
                          tags.get("build_host"), av.getBuildJobName(), av.getBuildNumber()))
              .orElse(null);

      return new AzureImageDetails(imageName, region, resourceGroup, osType, uri, jenkinsDetails);
    }

    @Override
    public int compareTo(AzureManagedImage other) {
      // Sort by name (reverse alphabetical to get latest versions)
      return other.imageName.compareTo(this.imageName);
    }
  }

  static class AzureImageDetails extends HashMap<String, Object> implements ImageDetails {
    AzureImageDetails(
        String imageName,
        String region,
        String resourceGroup,
        String osType,
        String uri,
        JenkinsDetails jenkinsDetails) {
      put("imageName", imageName);
      String imageId = (uri != null && !uri.isEmpty() && !uri.equals("na")) ? uri : "na";
      put("imageId", imageId);
      put("region", region);
      put("resourceGroup", resourceGroup);
      put("osType", osType);

      if (jenkinsDetails != null) {
        put("jenkins", jenkinsDetails);
      }
    }

    @Override
    public String getImageId() {
      return (String) get("imageId");
    }

    @Override
    public String getImageName() {
      return (String) get("imageName");
    }

    @Override
    public String getRegion() {
      return (String) get("region");
    }

    @Override
    public JenkinsDetails getJenkins() {
      return (JenkinsDetails) get("jenkins");
    }
  }
}
