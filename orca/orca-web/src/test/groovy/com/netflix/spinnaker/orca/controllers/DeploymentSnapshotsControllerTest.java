/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 */

package com.netflix.spinnaker.orca.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionType;
import com.netflix.spinnaker.orca.api.pipeline.models.PipelineExecution;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.pipeline.model.DefaultTrigger;
import com.netflix.spinnaker.orca.pipeline.model.PipelineExecutionImpl;
import com.netflix.spinnaker.orca.pipeline.model.StageExecutionImpl;
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@ExtendWith(SpringExtension.class)
@SpringBootTest(
    classes = {DeploymentSnapshotsController.class},
    webEnvironment = MOCK)
@AutoConfigureMockMvc
@EnableWebMvc
@WithMockUser("dashboard")
class DeploymentSnapshotsControllerTest {

  @MockBean ExecutionRepository executionRepository;
  @MockBean com.netflix.spinnaker.orca.front50.Front50Service front50Service;

  @Autowired MockMvc mvc;

  @Test
  void returnsProjectedSummaryForBatchOfApplications() throws Exception {
    when(executionRepository.retrievePipelineExecutionsForApplications(
            any(), any(), any(), anyInt()))
        .thenReturn(List.of(buildExecution("svc-a", "exec-1"), buildExecution("svc-b", "exec-2")));

    mvc.perform(get("/deploymentSnapshots").param("applications", "svc-a,svc-b"))
        .andExpect(status().is2xxSuccessful())
        // Both apps are represented in the response.
        .andExpect(
            jsonPath("$[*].application")
                .value(org.hamcrest.Matchers.containsInAnyOrder("svc-a", "svc-b")))
        .andExpect(jsonPath("$[0].stages").doesNotExist())
        .andExpect(jsonPath("$[0].trigger.type").value("manual"));
  }

  @Test
  void includeStagesReturnsTheStageGraphWithoutStageContext() throws Exception {
    PipelineExecution exec = buildExecution("svc-a", "exec-4");
    StageExecutionImpl parent = (StageExecutionImpl) exec.getStages().get(0);
    parent.setContext(Map.of("clusters", List.of("a", "b", "c")));
    parent.setOutputs(Map.of("bulky", "x".repeat(4096)));

    StageExecutionImpl window = new StageExecutionImpl();
    window.setExecution(exec);
    window.setId("stage-2");
    window.setRefId("2");
    window.setName("Release window");
    window.setType("restrictExecutionDuringTimeWindow");
    window.setStatus(ExecutionStatus.RUNNING);
    window.setParentStageId("stage-1");
    window.setRequisiteStageRefIds(List.of("1"));
    exec.getStages().add(window);

    when(executionRepository.retrievePipelineExecutionsForApplications(
            any(), any(), any(), anyInt()))
        .thenReturn(List.of(exec));

    mvc.perform(
            get("/deploymentSnapshots")
                .param("applications", "svc-a")
                .param("includeStages", "true"))
        .andExpect(status().is2xxSuccessful())
        .andExpect(jsonPath("$[0].stages.length()").value(2))
        .andExpect(jsonPath("$[0].stages[1].id").value("stage-2"))
        .andExpect(jsonPath("$[0].stages[1].refId").value("2"))
        .andExpect(jsonPath("$[0].stages[1].type").value("restrictExecutionDuringTimeWindow"))
        .andExpect(jsonPath("$[0].stages[1].status").value("RUNNING"))
        .andExpect(jsonPath("$[0].stages[1].parentStageId").value("stage-1"))
        .andExpect(jsonPath("$[0].stages[1].requisiteStageRefIds[0]").value("1"))
        // The point of the projection: the stage graph crosses the wire, the
        // per-stage payload that dominates an execution body does not.
        .andExpect(jsonPath("$[0].stages[0].context").doesNotExist())
        .andExpect(jsonPath("$[0].stages[0].outputs").doesNotExist())
        .andExpect(jsonPath("$[0].stages[0].tasks").doesNotExist());
  }

  @Test
  void includeStagesLiftsSkippedWaitAndLastModifiedOutOfContext() throws Exception {
    PipelineExecution exec = buildExecution("svc-a", "exec-5");
    StageExecutionImpl soak = (StageExecutionImpl) exec.getStages().get(0);
    soak.setRefId("soak");
    soak.setStatus(ExecutionStatus.RUNNING);
    // A stage PATCH from the UI writes the string form; typed tasks write a boolean.
    soak.setContext(Map.of("skipRemainingWait", "true"));
    StageExecution.LastModifiedDetails lastModified = new StageExecution.LastModifiedDetails();
    lastModified.setUser("alice@example.com");
    lastModified.setLastModifiedTime(1_700_000_000_000L);
    soak.setLastModified(lastModified);

    when(executionRepository.retrievePipelineExecutionsForApplications(
            any(), any(), any(), anyInt()))
        .thenReturn(List.of(exec));

    mvc.perform(
            get("/deploymentSnapshots")
                .param("applications", "svc-a")
                .param("includeStages", "true"))
        .andExpect(status().is2xxSuccessful())
        .andExpect(jsonPath("$[0].stages[0].skipRemainingWait").value(true))
        .andExpect(jsonPath("$[0].stages[0].lastModified.user").value("alice@example.com"))
        .andExpect(
            jsonPath("$[0].stages[0].lastModified.lastModifiedTime").value(1_700_000_000_000L));
  }

  @Test
  void stagesWithoutASkippedWaitOmitTheFlagEntirely() throws Exception {
    when(executionRepository.retrievePipelineExecutionsForApplications(
            any(), any(), any(), anyInt()))
        .thenReturn(List.of(buildExecution("svc-a", "exec-6")));

    mvc.perform(
            get("/deploymentSnapshots")
                .param("applications", "svc-a")
                .param("includeStages", "true"))
        .andExpect(status().is2xxSuccessful())
        .andExpect(jsonPath("$[0].stages[0].skipRemainingWait").doesNotExist())
        .andExpect(jsonPath("$[0].stages[0].lastModified").doesNotExist());
  }

  @Test
  void emptyApplicationsListShortCircuits() throws Exception {
    mvc.perform(get("/deploymentSnapshots").param("applications", ""))
        .andExpect(status().is2xxSuccessful())
        .andExpect(content().json("[]"));

    // Repo must not be touched when no apps are requested — we don't want to
    // accidentally trigger a full-table scan.
    verify(executionRepository, never())
        .retrievePipelineExecutionsForApplications(any(), any(), any(), anyInt());
  }

  @Test
  void projectsFailureMessageFromStageContext() throws Exception {
    PipelineExecution exec = buildExecution("svc-a", "exec-3");
    StageExecutionImpl failedStage = (StageExecutionImpl) exec.getStages().get(0);
    failedStage.setStatus(ExecutionStatus.TERMINAL);
    failedStage.setContext(
        Map.of(
            "exception",
            Map.of("details", Map.of("errors", List.of("ASG never reached desired capacity")))));

    when(executionRepository.retrievePipelineExecutionsForApplications(
            any(), any(), any(), anyInt()))
        .thenReturn(List.of(exec));

    mvc.perform(get("/deploymentSnapshots").param("applications", "svc-a"))
        .andExpect(status().is2xxSuccessful())
        .andExpect(jsonPath("$[0].failureMessage").value("ASG never reached desired capacity"));
  }

  private PipelineExecution buildExecution(String application, String id) {
    PipelineExecutionImpl exec = new PipelineExecutionImpl(ExecutionType.PIPELINE, application);
    exec.setId(id);
    exec.setName("deploy-" + application);
    exec.setStatus(ExecutionStatus.SUCCEEDED);
    exec.setStartTime(1_000L);
    exec.setEndTime(2_000L);
    exec.setTrigger(new DefaultTrigger("manual", null, "alice"));

    StageExecutionImpl stage = new StageExecutionImpl();
    stage.setExecution(exec);
    stage.setId("stage-1");
    stage.setRefId("1");
    stage.setName("Deploy");
    stage.setType("deploy");
    stage.setStatus(ExecutionStatus.SUCCEEDED);
    exec.getStages().add(stage);
    return exec;
  }
}
