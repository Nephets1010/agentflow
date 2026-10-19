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
package io.github.nephets.agentflow.sample;

import io.github.nephets.agentflow.core.WorkflowDefinition;
import io.github.nephets.agentflow.core.WorkflowEngine;
import io.github.nephets.agentflow.core.WorkflowRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AgentflowSampleApplicationTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private WorkflowEngine engine;

    @Autowired private WorkflowRegistry registry;

    @Autowired private WorkflowDefinition contract;

    @Test
    void contextRegistersBeansAndWorkflow() {
        assertThat(contract).isNotNull();
        assertThat(registry.ids()).contains(ContractApprovalWorkflow.WORKFLOW_ID);
        assertThat(engine.getToolRegistry().names()).contains(NoticeTool.NAME);
    }

    @Test
    void lowRiskRunFinishesWithoutApproval() throws Exception {
        mockMvc.perform(
                        post("/agentflow/runs")
                                .header("X-Tenant-Id", "acme")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"workflowId\":\""
                                                + ContractApprovalWorkflow.WORKFLOW_ID
                                                + "\",\"variables\":{\"contractText\":\"小额采购\",\"partyA\":\"ACME\",\"amount\":1000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.variables.risk").value("LOW"))
                .andExpect(jsonPath("$.variables.notified").value(true));
    }

    @Test
    void highRiskRunSuspendsAndCanBeApproved() throws Exception {
        String body =
                "{\"workflowId\":\""
                        + ContractApprovalWorkflow.WORKFLOW_ID
                        + "\",\"variables\":{\"contractText\":\"大额采购\",\"partyA\":\"ACME\",\"amount\":1500000}}";
        String response =
                mockMvc.perform(
                                post("/agentflow/runs")
                                        .header("X-Tenant-Id", "acme")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"))
                        .andExpect(
                                jsonPath("$.pendingApproval.stepId")
                                        .value(ContractApprovalWorkflow.STEP_RISK))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String runId = extractRunId(response);

        mockMvc.perform(get("/agentflow/runs/" + runId).header("X-Tenant-Id", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"));

        mockMvc.perform(
                        post("/agentflow/runs/" + runId + "/approve")
                                .header("X-Tenant-Id", "acme")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"APPROVE\",\"comment\":\"同意签署\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.variables.notified").value(true));
    }

    private static String extractRunId(String json) {
        int start = json.indexOf("\"runId\":\"") + 9;
        return json.substring(start, json.indexOf('"', start));
    }
}
