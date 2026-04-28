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

package com.netflix.spinnaker.orca.pipeline.util;

import com.google.common.collect.ImmutableList;
import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import com.netflix.spinnaker.kork.artifacts.model.ExpectedArtifact;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Post-processes resolved artifacts to replace {@code docker/image:latest} (and any other moving
 * tag aliases) with their canonical pinned-version equivalent. Applied after {@link
 * ArtifactResolver#resolveExpectedArtifacts} so matching against {@code matchArtifact} predicates
 * still uses the original (pre-canonicalization) values, preserving backward compatibility for
 * pipelines that match on a literal {@code latest} version.
 *
 * <p>If no registered {@link DockerLatestResolver} handles an artifact's reference, the artifact
 * is left unchanged (defensive default — non-ECR registries or unrecognized providers are passed
 * through rather than failing the resolution).
 */
@Component
@Slf4j
public class DockerLatestResolutionService {
  private static final String DOCKER_IMAGE_TYPE = "docker/image";
  private static final String LATEST = "latest";

  private final List<DockerLatestResolver> resolvers;

  @Autowired
  public DockerLatestResolutionService(@Nullable List<DockerLatestResolver> resolvers) {
    this.resolvers = resolvers != null ? resolvers : Collections.emptyList();
  }

  public Artifact canonicalize(@Nullable Artifact artifact) {
    if (artifact == null || !needsCanonicalization(artifact)) {
      return artifact;
    }
    for (DockerLatestResolver resolver : resolvers) {
      if (resolver.handles(artifact)) {
        Artifact canonical = resolver.canonicalize(artifact);
        log.info(
            "canonicalized docker artifact reference {} -> {} (version {} -> {})",
            artifact.getReference(),
            canonical.getReference(),
            artifact.getVersion(),
            canonical.getVersion());
        return canonical;
      }
    }
    log.debug(
        "no DockerLatestResolver handled reference {}; passing through unchanged",
        artifact.getReference());
    return artifact;
  }

  public ArtifactResolver.ResolveResult canonicalize(ArtifactResolver.ResolveResult result) {
    if (resolvers.isEmpty()) {
      return result;
    }
    ImmutableList<Artifact> resolvedArtifacts =
        result.getResolvedArtifacts().stream()
            .map(this::canonicalize)
            .collect(ImmutableList.toImmutableList());
    ImmutableList<ExpectedArtifact> resolvedExpectedArtifacts =
        result.getResolvedExpectedArtifacts().stream()
            .map(
                ea ->
                    ea.getBoundArtifact() == null
                        ? ea
                        : ea.toBuilder().boundArtifact(canonicalize(ea.getBoundArtifact())).build())
            .collect(ImmutableList.toImmutableList());
    return ArtifactResolver.ResolveResult.create(resolvedArtifacts, resolvedExpectedArtifacts);
  }

  private static boolean needsCanonicalization(Artifact artifact) {
    if (!DOCKER_IMAGE_TYPE.equals(artifact.getType())) {
      return false;
    }
    if (LATEST.equals(artifact.getVersion())) {
      return true;
    }
    String reference = artifact.getReference();
    return reference != null && reference.endsWith(":" + LATEST);
  }
}
