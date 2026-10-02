/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 */

package com.netflix.spinnaker.gate.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.netflix.spinnaker.gate.services.DeploymentSnapshotService.Section;
import com.netflix.spinnaker.gate.services.DeploymentSnapshotService.Snapshot;
import com.netflix.spinnaker.gate.services.internal.ClouddriverService;
import com.netflix.spinnaker.gate.services.internal.ClouddriverServiceSelector;
import com.netflix.spinnaker.gate.services.internal.Front50Service;
import com.netflix.spinnaker.gate.services.internal.OrcaService;
import com.netflix.spinnaker.gate.services.internal.OrcaServiceSelector;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import retrofit2.mock.Calls;

class DeploymentSnapshotServiceTest {

  private ClouddriverService clouddriver;
  private OrcaService orca;
  private Front50Service front50;
  private DeploymentSnapshotService service;

  @BeforeEach
  void setUp() {
    clouddriver = mock(ClouddriverService.class);
    orca = mock(OrcaService.class);
    front50 = mock(Front50Service.class);

    ClouddriverServiceSelector clouddriverSelector = mock(ClouddriverServiceSelector.class);
    when(clouddriverSelector.select()).thenReturn(clouddriver);
    OrcaServiceSelector orcaSelector = mock(OrcaServiceSelector.class);
    when(orcaSelector.select()).thenReturn(orca);

    when(clouddriver.getServerGroups(any(), any(), any()))
        .thenReturn(Calls.response(List.of(Map.of("application", "svc-a", "name", "svc-a-v001"))));
    when(front50.getAllPipelineConfigs())
        .thenReturn(
            Calls.response(
                List.of(Map.of("application", "svc-a", "name", "deploy-dev", "id", "cfg-1"))));
    when(orca.getDeploymentSnapshots(any(), any(), any(), any(), anyBoolean()))
        .thenReturn(
            Calls.response(
                List.of(Map.of("application", "svc-a", "id", "exec-1", "status", "RUNNING"))));

    service = new DeploymentSnapshotService(clouddriverSelector, orcaSelector, front50);
  }

  @Test
  void returnsAllThreeSectionsByDefault() {
    Snapshot snapshot =
        service.getSnapshot(List.of("svc-a"), List.of(), 2, null, false, Section.ALL);

    assertThat(snapshot.apps).hasSize(1);
    assertThat(snapshot.apps.get(0).serverGroups).hasSize(1);
    assertThat(snapshot.apps.get(0).pipelineConfigs).hasSize(1);
    assertThat(snapshot.apps.get(0).executions).hasSize(1);
  }

  /**
   * Non-invocation is the behaviour under test, not an incidental mechanism, so this asserts on the
   * mocks as well as the response shape. An implementation that fetched front50's whole pipeline
   * corpus and then discarded it would satisfy the empty-list assertions while losing the entire
   * point of the parameter — the multi-MB payload that never crosses the wire.
   */
  @Test
  void executionsOnlyDoesNotCallClouddriverOrFront50() {
    Snapshot snapshot =
        service.getSnapshot(
            List.of("svc-a"), List.of(), 2, "RUNNING", true, Set.of(Section.EXECUTIONS));

    assertThat(snapshot.apps.get(0).executions).hasSize(1);
    assertThat(snapshot.apps.get(0).serverGroups).isEmpty();
    assertThat(snapshot.apps.get(0).pipelineConfigs).isEmpty();

    verify(clouddriver, never()).getServerGroups(any(), any(), any());
    verify(front50, never()).getAllPipelineConfigs();
  }

  @Test
  void serverGroupsOnlyDoesNotCallOrcaOrFront50() {
    Snapshot snapshot =
        service.getSnapshot(
            List.of("svc-a"), List.of(), 2, null, false, Set.of(Section.SERVER_GROUPS));

    assertThat(snapshot.apps.get(0).serverGroups).hasSize(1);
    assertThat(snapshot.apps.get(0).executions).isEmpty();

    verify(orca, never()).getDeploymentSnapshots(any(), any(), any(), any(), anyBoolean());
    verify(front50, never()).getAllPipelineConfigs();
  }

  @Test
  void includeStagesIsForwardedToOrca() {
    service.getSnapshot(
        List.of("svc-a"), List.of(), 2, "RUNNING", true, Set.of(Section.EXECUTIONS));

    verify(orca).getDeploymentSnapshots(List.of("svc-a"), List.of(), "RUNNING", 2, true);
  }
}
