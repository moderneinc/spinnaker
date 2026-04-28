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

package com.netflix.spinnaker.orca.clouddriver;

import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import com.netflix.spinnaker.kork.retrofit.Retrofit2SyncCall;
import com.netflix.spinnaker.orca.pipeline.util.DockerLatestResolver;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves docker/image artifacts whose reference points at an ECR registry by delegating to
 * clouddriver's {@code /ecs/images/resolveDockerTag} endpoint, which performs the digest→tag
 * lookup using the existing AWS credentials repository.
 */
@Component
public class EcrDockerLatestResolver implements DockerLatestResolver {
  private static final Pattern ECR_REFERENCE =
      Pattern.compile(
          "^(?:https?://)?\\d{12}\\.dkr\\.ecr\\.[a-z0-9-]+\\.amazonaws\\.com/.+:.+$");

  private final OortService oortService;

  @Autowired
  public EcrDockerLatestResolver(OortService oortService) {
    this.oortService = oortService;
  }

  @Override
  public boolean handles(Artifact artifact) {
    String reference = artifact.getReference();
    return reference != null && ECR_REFERENCE.matcher(reference).matches();
  }

  @Override
  public Artifact canonicalize(Artifact artifact) {
    Map<String, String> response =
        Retrofit2SyncCall.execute(oortService.resolveDockerTag(artifact.getReference()));
    String resolvedTag = response.get("resolvedTag");
    String resolvedReference = response.get("reference");
    if (resolvedTag == null || resolvedReference == null) {
      throw new IllegalStateException(
          "clouddriver resolveDockerTag returned an incomplete response: " + response);
    }
    return artifact.toBuilder().version(resolvedTag).reference(resolvedReference).build();
  }
}
