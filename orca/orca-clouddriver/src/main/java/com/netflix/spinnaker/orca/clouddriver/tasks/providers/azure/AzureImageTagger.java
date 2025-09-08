package com.netflix.spinnaker.orca.clouddriver.tasks.providers.azure;

import static java.util.stream.Collectors.toList;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.clouddriver.OortService;
import com.netflix.spinnaker.orca.clouddriver.tasks.image.ImageTagger;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AzureImageTagger extends ImageTagger {
  private static final String CLOUD_PROVIDER = "azure";

  @Autowired
  public AzureImageTagger(OortService oortService, ObjectMapper objectMapper) {
    super(oortService, objectMapper);
  }

  @Override
  protected String getCloudProvider() {
    return CLOUD_PROVIDER;
  }

  @Override
  protected OperationContext getOperationContext(StageExecution stage) {
    StageData stageData = stage.mapTo(StageData.class);

    Collection<MatchedImage> matchedImages =
        findImages(stageData.imageNames, stageData.consideredStages, stage, MatchedImage.class);

    if (matchedImages.isEmpty()) {
      throw new IllegalStateException("No images found for tagging");
    }

    List<Image> targetImages = new ArrayList<>();
    List<Map<String, Map>> operations = new ArrayList<>();

    matchedImages.stream()
        .filter(matchedImage -> matchedImage.isCustom)
        .forEach(
            matchedImage -> {
              Map<String, String> tags = stageData.tags;
              Image targetImage =
                  new Image(
                      matchedImage.imageName,
                      matchedImage.account,
                      Collections.singletonList(matchedImage.region),
                      tags);
              targetImages.add(targetImage);

              Map<String, Object> operation = new HashMap<>();
              operation.put("imageName", matchedImage.imageName);
              operation.put("imageId", matchedImage.imageId);
              operation.put("resourceGroupName", matchedImage.resourceGroup);
              operation.put("credentials", matchedImage.account);
              operation.put("region", matchedImage.region);
              operation.put("regions", Collections.singletonList(matchedImage.region));
              operation.put("tags", tags);
              operation.put("isCustomImage", true);

              operations.add(Collections.singletonMap(OPERATION, operation));
            });

    Map<String, Object> extraOutput = new HashMap<>();
    extraOutput.put("targets", targetImages);

    return new OperationContext(operations, extraOutput);
  }

  @Override
  protected Collection<String> upstreamImageIds(
      StageExecution sourceStage,
      Collection<String> consideredStageRefIds,
      String cloudProviderType) {
    Collection<String> imageIds =
        super.upstreamImageIds(sourceStage, consideredStageRefIds, cloudProviderType);

    // Azure bake stages output full resource IDs, extract just the image name
    return imageIds.stream()
        .map(
            imageId -> {
              if (imageId.startsWith("/subscriptions/")) {
                // Extract image name from resource ID like:
                // /subscriptions/.../resourceGroups/.../providers/Microsoft.Compute/images/imageName
                String[] parts = imageId.split("/");
                return parts[parts.length - 1];
              }
              return imageId;
            })
        .collect(toList());
  }

  @Override
  protected boolean areImagesTagged(
      Collection<Image> targetImages, Collection<String> consideredStages, StageExecution stage) {

    // returning true for now as appears force cache refresh is not working for images
    // this can cause the pipeline to wait while the cache updates on its own schedule
    /*
    for (Image targetImage : targetImages) {
      Map<String, String> additionalFilters = new HashMap<>();
      additionalFilters.put("managedImages", "true");

      List<Map> foundImages =
          oortService.findImage(
              getCloudProvider(),
              targetImage.imageName,
              targetImage.account,
              null,
              additionalFilters);

      if (foundImages.isEmpty()) {
        return false;
      }

      Map foundImage = foundImages.get(0);
      Map<String, String> imageTags = (Map<String, String>) foundImage.get("tags");

      if (imageTags == null) {
        return false;
      }

      for (Map.Entry<String, String> targetTag : targetImage.tags.entrySet()) {
        if (!targetTag.getValue().equals(imageTags.get(targetTag.getKey()))) {
          return false;
        }
      }
    }
    */

    return true;
  }

  static class StageData {
    public Collection<String> imageNames = Collections.emptyList();
    public Set<String> consideredStages = new HashSet<>();
    public Map<String, String> tags = new HashMap<>();
  }

  static class MatchedImage {
    @JsonProperty String imageId;

    @JsonProperty String imageName;

    @JsonProperty String account;

    @JsonProperty String region;

    @JsonProperty String resourceGroup;

    @JsonProperty Boolean isCustom;
  }
}
