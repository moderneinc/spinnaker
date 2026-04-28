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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.mock.Calls;

final class EcrDockerLatestResolverTest {

  private static final String LATEST_REF =
      "297794628946.dkr.ecr.us-west-2.amazonaws.com/moderne/recipe-worker-arm64:latest";
  private static final String RESOLVED_REF =
      "297794628946.dkr.ecr.us-west-2.amazonaws.com/moderne/recipe-worker-arm64:0.147.3";

  private OortService oortService;
  private EcrDockerLatestResolver target;

  @BeforeEach
  void setUp() {
    oortService = mock(OortService.class);
    target = new EcrDockerLatestResolver(oortService);
  }

  @Test
  void handles_ecrReference() {
    Artifact ecr =
        Artifact.builder().type("docker/image").reference(LATEST_REF).build();
    assertThat(target.handles(ecr)).isTrue();
  }

  @Test
  void doesNotHandle_dockerHubReference() {
    Artifact dockerHub =
        Artifact.builder().type("docker/image").reference("docker.io/library/nginx:latest").build();
    assertThat(target.handles(dockerHub)).isFalse();
  }

  @Test
  void doesNotHandle_nullReference() {
    Artifact noRef = Artifact.builder().type("docker/image").build();
    assertThat(target.handles(noRef)).isFalse();
  }

  @Test
  void canonicalize_callsOortServiceAndRewritesArtifact() {
    Call<Map<String, String>> call =
        Calls.response(Map.of("resolvedTag", "0.147.3", "reference", RESOLVED_REF));
    when(oortService.resolveDockerTag(eq(LATEST_REF))).thenReturn(call);

    Artifact input =
        Artifact.builder()
            .type("docker/image")
            .name("moderne/recipe-worker-arm64")
            .version("latest")
            .reference(LATEST_REF)
            .build();
    Artifact result = target.canonicalize(input);
    assertThat(result.getVersion()).isEqualTo("0.147.3");
    assertThat(result.getReference()).isEqualTo(RESOLVED_REF);
    assertThat(result.getName()).isEqualTo("moderne/recipe-worker-arm64");
  }

  @Test
  void canonicalize_oortReturnsIncompleteResponse_throws() {
    Call<Map<String, String>> call = Calls.response(Map.of("resolvedTag", "0.147.3"));
    when(oortService.resolveDockerTag(eq(LATEST_REF))).thenReturn(call);

    Artifact input = Artifact.builder().type("docker/image").reference(LATEST_REF).build();
    assertThatThrownBy(() -> target.canonicalize(input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("incomplete response");
  }

  @Test
  void canonicalize_oortPropagatesErrorAsRuntime() {
    Call<Map<String, String>> call =
        Calls.response(
            Response.error(
                404,
                ResponseBody.create("not found", MediaType.parse("text/plain"))));
    when(oortService.resolveDockerTag(eq(LATEST_REF))).thenReturn(call);

    Artifact input = Artifact.builder().type("docker/image").reference(LATEST_REF).build();
    assertThatThrownBy(() -> target.canonicalize(input)).isInstanceOf(RuntimeException.class);
  }
}
