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

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 步骤 / 运行状态机的引擎级测试：每条合法迁移走通，每个非法操作被显式拒绝。
 *
 * <p>状态机的价值全在“拒绝”上：放行非法迁移只会让补偿漏撤销、回退跳步骤， 因此这里对重复裁决、终态恢复、 审批单作废等边界逐条钉死。
 */
class StepStateMachineTest {

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

    private WorkflowInstance instance(String runId) {
        return engine.find("acme", runId).orElseThrow();
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
    void firstStepObservesRunAlreadyRunning() {
        AtomicReference<RunStatus> seenStatus = new AtomicReference<>();
        AtomicReference<StepStatus> seenStepStatus = new AtomicReference<>();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step(
                                "a",
                                ctx -> {
                                    seenStatus.set(instance(ctx.getRunId()).getStatus());
                                    seenStepStatus.set(instance(ctx.getRunId()).stepStatus("a"));
                                    return StepResult.success();
                                }));

        engine.start(definition, "acme", Map.of());

        // 处理器执行时运行已落库为 RUNNING、本步已记为 RUNNING：状态迁移先于副作用。
        assertThat(seenStatus.get()).isEqualTo(RunStatus.RUNNING);
        assertThat(seenStepStatus.get()).isEqualTo(StepStatus.RUNNING);
    }

    @Test
    void happyPathEndsSucceededWithEveryStepSucceeded() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step("a", ctx -> StepResult.success(Map.of("a", 1))),
                        step("b", ctx -> StepResult.success(Map.of("b", 2))));

        String runId = engine.start(definition, "acme", Map.of());

        WorkflowInstance instance = instance(runId);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance.getCurrentStepIndex()).isEqualTo(2);
        assertThat(instance.getStepStatuses().values())
                .containsExactly(StepStatus.SUCCEEDED, StepStatus.SUCCEEDED);
    }

    @Test
    void failurePersistsFailedThenCompensatingThenCompensated() {
        AtomicReference<RunStatus> seenDuringCompensation = new AtomicReference<>();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success(Map.of("a", 1)))
                                .compensator(
                                        ctx -> {
                                            seenDuringCompensation.set(
                                                    store.findById("acme", ctx.getRunId())
                                                            .orElseThrow()
                                                            .getStatus());
                                            return CompensationResult.ok("撤销 a");
                                        })
                                .build(),
                        step("b", ctx -> StepResult.failure("校验不通过")));

        String runId = engine.start(definition, "acme", Map.of());

        // 补偿器执行期间运行必须是 COMPENSATING：失败不是一步跳到 COMPENSATED，中间态同样落库。
        assertThat(seenDuringCompensation.get()).isEqualTo(RunStatus.COMPENSATING);
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.COMPENSATED);
        assertThat(instance(runId).stepStatus("b")).isEqualTo(StepStatus.FAILED);
        assertThat(instance(runId).stepStatus("a")).isEqualTo(StepStatus.COMPENSATED);
    }

    @Test
    void rollbackMovesToRolledBackAndDropsFutureSnapshots() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step("a", ctx -> StepResult.success(Map.of("a", 1))),
                        step("b", ctx -> StepResult.success(Map.of("b", 2))));
        String runId = engine.start(definition, "acme", Map.of("seed", 0));

        engine.rollbackTo("acme", runId, 2);

        WorkflowInstance instance = instance(runId);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.ROLLED_BACK);
        assertThat(instance.getCurrentStepIndex()).isEqualTo(1);
        assertThat(instance.stepStatus("a")).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(instance.stepStatus("b")).isEqualTo(StepStatus.RUNNING);
        assertThat(instance.getSnapshots())
                .extracting(RunSnapshot::getVersion)
                .containsExactly(1, 2);
    }

    @Test
    void duplicateApproveIsRejectedAfterFirstDecision() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success())
                                .requiresApproval(true)
                                .build());
        String runId = engine.start(definition, "acme", Map.of());
        String approvalId = instance(runId).getPendingApproval().getApprovalId();

        engine.approve("acme", approvalId, ApprovalDecision.APPROVE, "同意");

        // 审批单一经裁决即作废：重复提交必须拒绝，否则同一步会被记两次成功、多推一次下标。
        assertThatThrownBy(
                        () -> engine.approve("acme", approvalId, ApprovalDecision.APPROVE, "再同意"))
                .isInstanceOf(WorkflowNotFoundException.class)
                .hasMessageContaining("未找到审批单");
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void approveRequiresNonNullDecision() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success())
                                .requiresApproval(true)
                                .build());
        String runId = engine.start(definition, "acme", Map.of());
        String approvalId = instance(runId).getPendingApproval().getApprovalId();

        assertThatThrownBy(() -> engine.approve("acme", approvalId, null, "同意"))
                .isInstanceOf(NullPointerException.class);
        // 决策缺失不能改动运行：仍挂着原审批单等待重新裁决。
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(instance(runId).getPendingApproval().getApprovalId()).isEqualTo(approvalId);
    }

    @Test
    void rollbackDuringSuspensionVoidsApprovalAndAllowsResume() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step("a", ctx -> StepResult.success(Map.of("a", 1))),
                        StepDefinition.builder("b", ctx -> StepResult.success())
                                .requiresApproval(true)
                                .build());
        String runId = engine.start(definition, "acme", Map.of());
        String oldApprovalId = instance(runId).getPendingApproval().getApprovalId();

        engine.rollbackTo("acme", runId, 1);

        WorkflowInstance rolledBack = instance(runId);
        assertThat(rolledBack.getStatus()).isEqualTo(RunStatus.ROLLED_BACK);
        assertThat(rolledBack.getPendingApproval()).isNull();
        assertThat(engine.pendingApprovals("acme")).isEmpty();
        // 旧审批单同步作废：拿着已回退前的审批单再来裁决，必须找不到。
        assertThatThrownBy(
                        () ->
                                engine.approve(
                                        "acme", oldApprovalId, ApprovalDecision.APPROVE, "迟到的同意"))
                .isInstanceOf(WorkflowNotFoundException.class);

        engine.resume("acme", runId);

        // 从快照点重跑会再次挂起，并生成一张新的审批单。
        WorkflowInstance resumed = instance(runId);
        assertThat(resumed.getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(resumed.getPendingApproval().getApprovalId()).isNotEqualTo(oldApprovalId);
    }

    @Test
    void resumeOnFinishedRunNeitherRestartsNorThrows() {
        List<String> visited = new ArrayList<>();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step(
                                "a",
                                ctx -> {
                                    visited.add("a");
                                    return StepResult.success();
                                }));
        String runId = engine.start(definition, "acme", Map.of());

        engine.resume("acme", runId);

        assertThat(visited).containsExactly("a");
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void resumeOnCompensatedRunIsAlsoSkipped() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success())
                                .compensator(ctx -> CompensationResult.ok("撤销"))
                                .build(),
                        step("b", ctx -> StepResult.failure("失败")));
        String runId = engine.start(definition, "acme", Map.of());

        engine.resume("acme", runId);

        // COMPENSATED 与 SUCCEEDED 一样是终态：重复恢复不会把已补偿的运行重新推起来。
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.COMPENSATED);
        assertThat(instance(runId).stepStatus("a")).isEqualTo(StepStatus.COMPENSATED);
    }

    @Test
    void engineUsesInjectedClockForTimeline() {
        Clock fixed =
                Clock.fixed(
                        java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);
        WorkflowEngine clocked =
                new WorkflowEngine(store, new WorkflowRegistry(), new ToolRegistry(), fixed, 50);
        WorkflowDefinition definition = workflow("wf", step("a", ctx -> StepResult.success()));

        String runId = clocked.start(definition, "acme", Map.of());

        WorkflowInstance instance = clocked.find("acme", runId).orElseThrow();
        assertThat(instance.getCreatedAt())
                .isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(instance.getSnapshots()).isNotEmpty();
        assertThat(instance.getSnapshots().get(0).getCreatedAt())
                .isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        clocked.close();
    }
}
