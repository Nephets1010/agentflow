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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 人工确认检查点的深度测试：挂起、恢复、重复裁决、跨租户可见性、审批意见的落地。
 *
 * <p>检查点是整套机制里唯一“等人”的地方，也是最容易出数据越权的地方： 审批单按租户隔离、裁决一次性， 这两条不变量在这里逐条钉死。
 */
class ApprovalCheckpointTest {

    private InMemoryRunStore store;
    private WorkflowEngine engine;

    private static StepDefinition step(String id, StepHandler handler) {
        return StepDefinition.builder(id, handler).build();
    }

    private static WorkflowDefinition workflow(String id, StepDefinition... steps) {
        WorkflowDefinition.Builder builder = WorkflowDefinition.builder().workflowId(id);
        for (StepDefinition definition : steps) {
            builder.addStep(definition);
        }
        return builder.build();
    }

    private WorkflowInstance instance(String tenantId, String runId) {
        return engine.find(tenantId, runId).orElseThrow();
    }

    @BeforeEach
    void setUp() {
        AgentflowTenant.clear();
        store = new InMemoryRunStore();
        engine = new WorkflowEngine(store);
    }

    @AfterEach
    void tearDown() {
        engine.close();
        AgentflowTenant.clear();
    }

    @Test
    void suspendRecordsWaitingApprovalOnStepAndSnapshot() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step("a", ctx -> StepResult.success(Map.of("a", 1))),
                        StepDefinition.builder("b", ctx -> StepResult.success())
                                .requiresApproval(true)
                                .build());

        String runId = engine.start(definition, "acme", Map.of());

        WorkflowInstance instance = instance("acme", runId);
        assertThat(instance.stepStatus("b")).isEqualTo(StepStatus.WAITING_APPROVAL);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        // 挂起这一刻也进快照：回退到挂起点时步骤状态必须是 WAITING_APPROVAL 而不是 PENDING。
        assertThat(instance.getSnapshots())
                .isNotEmpty()
                .anySatisfy(
                        snapshot -> {
                            assertThat(snapshot.getStepStatus())
                                    .isEqualTo(StepStatus.WAITING_APPROVAL);
                            assertThat(snapshot.getStepIndex()).isEqualTo(1);
                        });
        assertThat(engine.findByStatus("acme", RunStatus.WAITING_APPROVAL))
                .extracting(WorkflowInstance::getRunId)
                .containsExactly(runId);
    }

    @Test
    void multiCheckpointSuspendsTwiceAndCompletes() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success())
                                .requiresApproval(true)
                                .approvalPrompt("一审")
                                .build(),
                        StepDefinition.builder("b", ctx -> StepResult.success())
                                .requiresApproval(true)
                                .approvalPrompt("二审")
                                .build(),
                        step("c", ctx -> StepResult.success(Map.of("done", true))));

        String runId = engine.start(definition, "acme", Map.of());
        assertThat(instance("acme", runId).getPendingApproval().getPrompt()).isEqualTo("一审");

        engine.approve(
                "acme",
                instance("acme", runId).getPendingApproval().getApprovalId(),
                ApprovalDecision.APPROVE,
                "一审通过");
        assertThat(instance("acme", runId).getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(instance("acme", runId).getPendingApproval().getPrompt()).isEqualTo("二审");

        engine.approve(
                "acme",
                instance("acme", runId).getPendingApproval().getApprovalId(),
                ApprovalDecision.APPROVE,
                "二审通过");

        WorkflowInstance instance = instance("acme", runId);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance.getCurrentStepIndex()).isEqualTo(3);
        assertThat(instance.getVariables())
                .containsEntry("a.approvalComment", "一审通过")
                .containsEntry("b.approvalComment", "二审通过")
                .containsEntry("done", true);
        assertThat(engine.pendingApprovals("acme")).isEmpty();
    }

    @Test
    void approvalIsScopedToTenant() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success())
                                .requiresApproval(true)
                                .build());
        String acmeRun = engine.start(definition, "acme", Map.of());
        String approvalId = instance("acme", acmeRun).getPendingApproval().getApprovalId();

        // 另一个租户拿着同样的审批单 id 来裁决：必须找不到，而不是改掉别人的运行。
        assertThatThrownBy(
                        () -> engine.approve("globex", approvalId, ApprovalDecision.APPROVE, "越权"))
                .isInstanceOf(WorkflowNotFoundException.class)
                .hasMessageContaining("未找到审批单");
        assertThat(instance("acme", acmeRun).getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(engine.pendingApprovals("globex")).isEmpty();
        assertThat(engine.pendingApprovals("acme")).hasSize(1);
    }

    @Test
    void pendingApprovalsListOnlyOwnTenant() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success())
                                .requiresApproval(true)
                                .build());
        String acmeRun = engine.start(definition, "acme", Map.of());
        String globexRun = engine.start(definition, "globex", Map.of());

        assertThat(engine.pendingApprovals("acme"))
                .extracting(PendingApproval::getRunId)
                .containsExactly(acmeRun);
        assertThat(engine.pendingApprovals("globex"))
                .extracting(PendingApproval::getRunId)
                .containsExactly(globexRun);
    }

    @Test
    void nullCommentBecomesEmptyStringVariable() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success(Map.of("a", 1)))
                                .requiresApproval(true)
                                .build());
        String runId = engine.start(definition, "acme", Map.of());

        engine.approve(
                "acme",
                instance("acme", runId).getPendingApproval().getApprovalId(),
                ApprovalDecision.APPROVE,
                null);

        // 意见缺失落空串而不是 null：变量表出现 null 值会让后续步骤的字符串拼接炸 NPE。
        assertThat(instance("acme", runId).getVariables()).containsEntry("a.approvalComment", "");
    }

    @Test
    void defaultPromptDerivesFromStepName() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success())
                                .name("风控校验")
                                .requiresApproval(true)
                                .build());

        String runId = engine.start(definition, "acme", Map.of());

        PendingApproval pending = instance("acme", runId).getPendingApproval();
        assertThat(pending.getPrompt()).isEqualTo("需要人工确认：风控校验");
        assertThat(pending.getStepId()).isEqualTo("a");
        assertThat(pending.getRunId()).isEqualTo(runId);
        assertThat(pending.getCreatedAt()).isNotNull();
    }

    @Test
    void approvedStepCommentIsVisibleToLaterSteps() {
        List<Object> seen = new ArrayList<>();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success(Map.of("a", 1)))
                                .requiresApproval(true)
                                .build(),
                        step(
                                "b",
                                ctx -> {
                                    seen.add(ctx.get("a.approvalComment"));
                                    return StepResult.success();
                                }));

        String runId = engine.start(definition, "acme", Map.of());
        engine.approve(
                "acme",
                instance("acme", runId).getPendingApproval().getApprovalId(),
                ApprovalDecision.APPROVE,
                "金额已复核");

        assertThat(seen).containsExactly("金额已复核");
        assertThat(instance("acme", runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void rejectAtSecondCheckpointCompensatesOnlyApprovedStep() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success(Map.of("a", 1)))
                                .requiresApproval(true)
                                .compensator(ctx -> CompensationResult.ok("撤销一审副作用"))
                                .build(),
                        StepDefinition.builder("b", ctx -> StepResult.success())
                                .requiresApproval(true)
                                .compensator(ctx -> CompensationResult.ok("撤销二审副作用"))
                                .build(),
                        step("c", ctx -> StepResult.success(Map.of("c", 3))));

        String runId = engine.start(definition, "acme", Map.of());
        engine.approve(
                "acme",
                instance("acme", runId).getPendingApproval().getApprovalId(),
                ApprovalDecision.APPROVE,
                "一审通过");
        engine.approve(
                "acme",
                instance("acme", runId).getPendingApproval().getApprovalId(),
                ApprovalDecision.REJECT,
                "二审拒绝");

        WorkflowInstance instance = instance("acme", runId);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.COMPENSATED);
        // 只有已批准（SUCCEEDED）的步骤被补偿；被拒绝的步骤记 FAILED，未开跑的步骤保持 PENDING。
        assertThat(instance.stepStatus("a")).isEqualTo(StepStatus.COMPENSATED);
        assertThat(instance.stepStatus("b")).isEqualTo(StepStatus.FAILED);
        assertThat(instance.stepStatus("c")).isEqualTo(StepStatus.PENDING);
    }

    @Test
    void approveContinuesWithoutExtraResume() {
        List<String> visited = new ArrayList<>();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success())
                                .requiresApproval(true)
                                .build(),
                        step(
                                "b",
                                ctx -> {
                                    visited.add("b");
                                    return StepResult.success();
                                }));

        String runId = engine.start(definition, "acme", Map.of());
        engine.approve(
                "acme",
                instance("acme", runId).getPendingApproval().getApprovalId(),
                ApprovalDecision.APPROVE,
                "同意");

        // 批准即推进：不需要额外 resume，步骤 b 直接跟上（requiresApproval 的步骤处理器从不执行）。
        assertThat(visited).containsExactly("b");
        assertThat(instance("acme", runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance("acme", runId).stepStatus("a")).isEqualTo(StepStatus.SUCCEEDED);
    }
}
