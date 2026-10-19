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

import io.github.nephets.agentflow.core.AgentflowTenant;
import io.github.nephets.agentflow.core.ApprovalDecision;
import io.github.nephets.agentflow.core.InMemoryRunStore;
import io.github.nephets.agentflow.core.PendingApproval;
import io.github.nephets.agentflow.core.RunStatus;
import io.github.nephets.agentflow.core.StepStatus;
import io.github.nephets.agentflow.core.ToolRegistry;
import io.github.nephets.agentflow.core.WorkflowDefinition;
import io.github.nephets.agentflow.core.WorkflowEngine;
import io.github.nephets.agentflow.core.WorkflowInstance;
import io.github.nephets.agentflow.starter.AgentflowProperties;
import io.github.nephets.agentflow.starter.RuleBasedLlmClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContractApprovalWorkflowTest {

    private static final String TENANT = "acme";

    private ToolRegistry tools;
    private NoticeTool noticeTool;
    private WorkflowEngine engine;
    private WorkflowDefinition contract;

    private static Map<String, Object> vars(String text, String partyA, int amount) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("contractText", text);
        variables.put("partyA", partyA);
        variables.put("amount", amount);
        return variables;
    }

    @BeforeEach
    void setUp() {
        AgentflowTenant.clear();
        tools = new ToolRegistry();
        noticeTool = new NoticeTool();
        tools.register(noticeTool);
        AgentflowProperties properties = new AgentflowProperties();
        contract = ContractApprovalWorkflow.definition(new RuleBasedLlmClient(), tools, properties);
        engine =
                new WorkflowEngine(
                        new InMemoryRunStore(),
                        new io.github.nephets.agentflow.core.WorkflowRegistry(),
                        tools,
                        Clock.systemUTC(),
                        properties.getSnapshotLimit());
    }

    @AfterEach
    void tearDown() {
        AgentflowTenant.clear();
    }

    @Test
    void definitionHasThreeStepsInOrder() {
        assertThat(contract.getWorkflowId()).isEqualTo(ContractApprovalWorkflow.WORKFLOW_ID);
        assertThat(contract.getSteps())
                .extracting(step -> step.getStepId())
                .containsExactly(
                        ContractApprovalWorkflow.STEP_EXTRACT,
                        ContractApprovalWorkflow.STEP_RISK,
                        ContractApprovalWorkflow.STEP_NOTICE);
        assertThat(contract.step(ContractApprovalWorkflow.STEP_RISK).orElseThrow().hasCompensator())
                .isTrue();
        assertThat(
                        contract.step(ContractApprovalWorkflow.STEP_EXTRACT)
                                .orElseThrow()
                                .hasCompensator())
                .isTrue();
    }

    @Test
    void lowRiskContractSendsNotice() {
        String runId = engine.start(contract, TENANT, vars("小额采购合同", "ACME", 500_000));

        WorkflowInstance instance = engine.find(TENANT, runId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance.getVariables()).containsEntry("risk", "LOW");
        assertThat(noticeTool.sentMessages()).hasSize(1);
        assertThat(noticeTool.sentMessages().get(0)).contains("风险等级=LOW");
    }

    @Test
    void rejectedHighRiskContractIsCompensated() {
        String runId = engine.start(contract, TENANT, vars("大额采购合同", "Globex", 2_000_000));
        PendingApproval pending = engine.find(TENANT, runId).orElseThrow().getPendingApproval();
        assertThat(pending.getStepId()).isEqualTo(ContractApprovalWorkflow.STEP_RISK);

        AgentflowTenant.runWith(
                TENANT,
                () -> engine.approve(pending.getApprovalId(), ApprovalDecision.REJECT, "超出授权"));

        WorkflowInstance instance = engine.find(TENANT, runId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo(RunStatus.COMPENSATED);
        assertThat(instance.stepStatus(ContractApprovalWorkflow.STEP_RISK))
                .isEqualTo(StepStatus.FAILED);
        assertThat(instance.stepStatus(ContractApprovalWorkflow.STEP_EXTRACT))
                .isEqualTo(StepStatus.COMPENSATED);
        assertThat(noticeTool.sentMessages()).isEmpty();
    }

    @Test
    void approvedHighRiskContractSendsNotice() {
        String runId = engine.start(contract, TENANT, vars("大额采购合同", "ACME", 1_200_000));
        PendingApproval pending = engine.find(TENANT, runId).orElseThrow().getPendingApproval();

        AgentflowTenant.runWith(
                TENANT,
                () -> engine.approve(pending.getApprovalId(), ApprovalDecision.APPROVE, "同意"));

        WorkflowInstance instance = engine.find(TENANT, runId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(noticeTool.sentMessages().get(0)).contains("风险等级=HIGH");
    }

    @Test
    void flakyStepRecoversAfterTimeoutAndRetries() {
        WorkflowDefinition flaky =
                ContractApprovalWorkflow.flakyWorkflow("demo", 2, 300L, new AgentflowProperties());

        String runId = engine.start(flaky, TENANT, new LinkedHashMap<>());

        WorkflowInstance instance = engine.find(TENANT, runId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance.getVariables()).containsEntry("attempts", 3);
    }

    @Test
    void noticeToolRejectsMissingRisk() {
        assertThat(noticeTool.invoke(Map.of()).isOk()).isFalse();
        assertThat(noticeTool.invoke(Map.of("risk", "LOW")).isOk()).isTrue();
        assertThat(noticeTool.name()).isEqualTo(NoticeTool.NAME);
    }

    @Test
    void noticeToolTreatsNullArgsAsEmpty() {
        assertThat(noticeTool.invoke(null).isOk()).isFalse();
        assertThat(noticeTool.invoke(null).getError()).contains("risk");
    }

    @Test
    void noticeToolCapsRetainedHistory() {
        for (int i = 0; i < NoticeTool.MAX_RETAINED + 5; i++) {
            assertThat(noticeTool.invoke(Map.of("risk", "LOW", "runId", "run-" + i)).isOk())
                    .isTrue();
        }

        assertThat(noticeTool.sentMessages()).hasSize(NoticeTool.MAX_RETAINED);
        assertThat(noticeTool.sentMessages().get(0)).contains("run-5");
        assertThat(noticeTool.sentMessages().get(NoticeTool.MAX_RETAINED - 1))
                .contains("run-" + (NoticeTool.MAX_RETAINED + 4));
    }
}
