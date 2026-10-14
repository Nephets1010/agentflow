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
package io.github.nephets.agentflow.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 合同审批流程的端到端集成测试：抽取要素 → 风控校验（高风险挂起）→ 发送通知； 抽取与风控两步各自带补偿器，失败或拒绝时逆序撤销。 */
class WorkflowFlowIT {

    private static final int RISK_THRESHOLD = 1_000_000;

    private InMemoryRunStore store;
    private WorkflowEngine engine;
    private List<String> auditTrail;

    /** 合同审批工作流：抽取要素 → 风控校验 → 发送通知。 */
    private WorkflowDefinition contractWorkflow() {
        return WorkflowDefinition.builder()
                .workflowId("contract-approval")
                .name("合同审批")
                .addStep(
                        StepDefinition.builder(
                                        "extract",
                                        ctx -> {
                                            auditTrail.add("extract");
                                            return StepResult.success(
                                                    Map.of("amount", ctx.get("amount")));
                                        })
                                .name("抽取要素")
                                .compensator(
                                        ctx -> {
                                            auditTrail.add("revoke:extract");
                                            return CompensationResult.ok("已清除要素草稿");
                                        })
                                .build())
                .addStep(
                        StepDefinition.builder(
                                        "risk-check",
                                        ctx -> {
                                            int amount = (int) ctx.get("amount");
                                            if (amount > RISK_THRESHOLD) {
                                                return StepResult.waitApproval(
                                                        "合同金额 " + amount + " 超过阈值，请人工确认",
                                                        Map.of("risk", "HIGH"));
                                            }
                                            return StepResult.success(Map.of("risk", "LOW"));
                                        })
                                .name("风控校验")
                                .compensator(
                                        ctx -> {
                                            auditTrail.add("revoke:risk-record");
                                            return CompensationResult.ok("已撤销风控记录");
                                        })
                                .build())
                .addStep(
                        StepDefinition.builder(
                                        "notify",
                                        ctx -> {
                                            auditTrail.add("notify:" + ctx.get("risk"));
                                            return StepResult.success(Map.of("notified", true));
                                        })
                                .name("发送通知")
                                .build())
                .build();
    }

    @BeforeEach
    void setUp() {
        AgentflowTenant.clear();
        store = new InMemoryRunStore();
        engine = new WorkflowEngine(store);
        auditTrail = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        AgentflowTenant.clear();
    }

    @Test
    void lowRiskContractRunsToCompletion() {
        String runId = engine.start(contractWorkflow(), "acme", Map.of("amount", 500_000));

        WorkflowInstance instance = engine.find("acme", runId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance.getVariables())
                .containsEntry("risk", "LOW")
                .containsEntry("notified", true);
        assertThat(auditTrail).containsExactly("extract", "notify:LOW");
        assertThat(instance.stepStatus("extract")).isEqualTo(StepStatus.SUCCEEDED);
    }

    @Test
    void highRiskContractWaitsForApprovalThenFinishes() {
        String runId = engine.start(contractWorkflow(), "acme", Map.of("amount", 1_500_000));
        assertThat(engine.find("acme", runId).orElseThrow().getStatus())
                .isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(engine.find("acme", runId).orElseThrow().getVariables())
                .containsEntry("risk", "HIGH");

        String approvalId =
                engine.find("acme", runId).orElseThrow().getPendingApproval().getApprovalId();
        engine.approve("acme", approvalId, ApprovalDecision.APPROVE, "同意签署");

        WorkflowInstance instance = engine.find("acme", runId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance.stepStatus("risk-check")).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(instance.getVariables()).containsEntry("notified", true);
        assertThat(auditTrail).containsExactly("extract", "notify:HIGH");
    }

    @Test
    void rejectedContractIsCompensatedInReverseOrder() {
        String runId = engine.start(contractWorkflow(), "globex", Map.of("amount", 2_000_000));
        String approvalId =
                engine.find("globex", runId).orElseThrow().getPendingApproval().getApprovalId();

        engine.approve("globex", approvalId, ApprovalDecision.REJECT, "超授权");

        WorkflowInstance instance = engine.find("globex", runId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo(RunStatus.COMPENSATED);
        assertThat(instance.stepStatus("risk-check")).isEqualTo(StepStatus.FAILED);
        assertThat(instance.stepStatus("extract")).isEqualTo(StepStatus.COMPENSATED);
        assertThat(instance.stepStatus("notify")).isEqualTo(StepStatus.PENDING);
        assertThat(auditTrail).containsExactly("extract", "revoke:extract");
        assertThat(instance.getSnapshots()).isNotEmpty();
        assertThat(store.findAll("globex")).hasSize(1);
    }
}
