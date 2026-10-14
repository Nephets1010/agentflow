/*
 * Copyright 2026 The agentflow Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nephets.agentflow.starter;

import io.github.nephets.agentflow.core.AgentflowTenant;
import io.github.nephets.agentflow.core.InMemoryRunStore;
import io.github.nephets.agentflow.core.StepDefinition;
import io.github.nephets.agentflow.core.StepResult;
import io.github.nephets.agentflow.core.ToolRegistry;
import io.github.nephets.agentflow.core.WorkflowDefinition;
import io.github.nephets.agentflow.core.WorkflowEngine;
import io.github.nephets.agentflow.core.WorkflowRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowControllerTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private WorkflowRegistry registry;
    private WorkflowEngine engine;
    private MockMvc mockMvc;

    private static WorkflowDefinition approvalWorkflow() {
        return WorkflowDefinition.builder()
                .workflowId("wf")
                .name("待审批流程")
                .addStep(
                        StepDefinition.builder("a", ctx -> StepResult.success(Map.of("a", 1)))
                                .name("抽取")
                                .build())
                .addStep(
                        StepDefinition.builder("b", ctx -> StepResult.success(Map.of("b", 2)))
                                .name("复核")
                                .requiresApproval(true)
                                .approvalPrompt("请复核")
                                .build())
                .addStep(
                        StepDefinition.builder("c", ctx -> StepResult.success(Map.of("c", 3)))
                                .name("通知")
                                .build())
                .build();
    }

    @BeforeEach
    void setUp() {
        AgentflowTenant.clear();
        registry = new WorkflowRegistry();
        registry.register(approvalWorkflow());
        engine =
                new WorkflowEngine(
                        new InMemoryRunStore(),
                        registry,
                        new ToolRegistry(),
                        Clock.systemUTC(),
                        50);
        WorkflowController controller =
                new WorkflowController(engine, registry, new AgentflowProperties());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        AgentflowTenant.clear();
    }

    @Test
    void startReturnsRunView() throws Exception {
        mockMvc.perform(
                        post("/agentflow/runs")
                                .contentType(JSON)
                                .content("{\"workflowId\":\"wf\",\"variables\":{\"amount\":10}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").exists())
                .andExpect(jsonPath("$.tenantId").value("default"))
                .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.variables.amount").value(10))
                .andExpect(jsonPath("$.pendingApproval.prompt").value("请复核"));
    }

    @Test
    void startRejectsUnknownWorkflowAndMissingId() throws Exception {
        mockMvc.perform(
                        post("/agentflow/runs")
                                .contentType(JSON)
                                .content("{\"workflowId\":\"nope\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("工作流未注册：nope"));
        mockMvc.perform(post("/agentflow/runs").contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("缺少 workflowId"));
    }

    @Test
    void getReturnsRunOrNotFound() throws Exception {
        String runId = startRun();

        mockMvc.perform(get("/agentflow/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.snapshotVersions").isArray());
        mockMvc.perform(get("/agentflow/runs/run-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("运行不存在：run-missing"));
    }

    @Test
    void approveContinuesTheRun() throws Exception {
        String runId = startRun();

        mockMvc.perform(
                        post("/agentflow/runs/" + runId + "/approve")
                                .contentType(JSON)
                                .content("{\"decision\":\"APPROVE\",\"comment\":\"同意\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.variables.c").value(3))
                .andExpect(jsonPath("$.variables['b.approvalComment']").value("同意"))
                .andExpect(jsonPath("$.pendingApproval").doesNotExist());
    }

    @Test
    void approveValidatesDecisionAndRun() throws Exception {
        String runId = startRun();

        mockMvc.perform(
                        post("/agentflow/runs/" + runId + "/approve")
                                .contentType(JSON)
                                .content("{\"decision\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("decision 只能是 APPROVE 或 REJECT"));
        mockMvc.perform(
                        post("/agentflow/runs/run-missing/approve")
                                .contentType(JSON)
                                .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/agentflow/runs/" + runId + "/approve")
                                .contentType(JSON)
                                .content("{\"approvalId\":\"apr-x\",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("未找到审批单：apr-x"));
    }

    @Test
    void approveRejectsRunWithoutPendingApproval() throws Exception {
        WorkflowDefinition noApproval =
                WorkflowDefinition.builder()
                        .workflowId("plain")
                        .addStep(StepDefinition.builder("a", ctx -> StepResult.success()).build())
                        .build();
        registry.register(noApproval);
        String runId = engine.start(noApproval, "default", Map.of());

        mockMvc.perform(
                        post("/agentflow/runs/" + runId + "/approve")
                                .contentType(JSON)
                                .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("运行 " + runId + " 当前没有挂起的审批单"));
    }

    @Test
    void rollbackRestoresSnapshotVersion() throws Exception {
        String runId = startRun();

        mockMvc.perform(post("/agentflow/runs/" + runId + "/rollback").param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.currentStepIndex").value(0));
        mockMvc.perform(post("/agentflow/runs/" + runId + "/rollback").param("version", "999"))
                .andExpect(status().isBadRequest());
    }

    private String startRun() throws Exception {
        String response =
                mockMvc.perform(
                                post("/agentflow/runs")
                                        .contentType(JSON)
                                        .content("{\"workflowId\":\"wf\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        int start = response.indexOf("\"runId\":\"") + 9;
        return response.substring(start, response.indexOf('"', start));
    }
}
