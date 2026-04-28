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

import com.netflix.spinnaker.kork.artifacts.model.Artifact;

/**
 * Replaces a docker/image artifact whose tag is a moving alias (e.g. {@code latest}) with a
 * canonical pinned-version artifact. Implementations look up the registry to find a stable semver
 * tag that shares the same digest as the moving alias.
 *
 * <p>Implementations are registered via Spring and consulted by {@link
 * DockerLatestResolutionService}. An implementation should return {@code true} from {@link
 * #handles(Artifact)} only for references it knows how to resolve (e.g., a specific registry
 * pattern).
 */
public interface DockerLatestResolver {

  /** Whether this resolver knows how to canonicalize the given artifact's reference. */
  boolean handles(Artifact artifact);

  /**
   * Resolve the artifact's moving alias to its canonical pinned tag, returning a new artifact with
   * both {@code version} and {@code reference} updated. Implementations must throw if no canonical
   * tag can be determined; silent fall-through reintroduces the bug this resolver exists to fix.
   */
  Artifact canonicalize(Artifact artifact);
}
