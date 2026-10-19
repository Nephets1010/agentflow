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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowEngineTest {

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

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        AgentflowTenant.clear();
    }

    @Test
    void startRunsEveryStepAndSucceeds() {
        List<String> visited = new ArrayList<>();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step(
                                "a",
                                ctx -> {
                                    visited.add("a");
                                    return StepResult.success(Map.of("a", 1));
                                }),
                        step(
                                "b",
                                ctx -> {
                                    visited.add("b");
                                    return StepResult.success(Map.of("b", 2));
                                }));

        String runId = engine.start(definition, "acme", Map.of("seed", 0));

        assertThat(visited).containsExactly("a", "b");
        WorkflowInstance instance = instance(runId);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance.getCurrentStepIndex()).isEqualTo(2);
        assertThat(instance.getVariables())
                .containsEntry("seed", 0)
                .containsEntry("a", 1)
                .containsEntry("b", 2);
        assertThat(instance.stepStatus("a")).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(instance.stepStatus("b")).isEqualTo(StepStatus.SUCCEEDED);
    }

    @Test
    void startPersistsDefinitionAndGeneratesRunId() {
        WorkflowDefinition definition = workflow("wf", step("a", ctx -> StepResult.success()));

        String first = engine.start(definition, "acme", null);
        String second = engine.start(definition, "acme", null);

        assertThat(first).startsWith("run-");
        assertThat(second).isNotEqualTo(first);
        assertThat(store.size()).isEqualTo(2);
        assertThat(engine.getWorkflowRegistry().ids()).containsExactly("wf");
        assertThat(instance(first).getVariables()).isEmpty();
    }

    @Test
    void startValidatesArguments() {
        assertThatThrownBy(() -> engine.start(null, "acme", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                engine.start(
                                        workflow("wf", step("a", ctx -> StepResult.success())),
                                        " ",
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlerExceptionTriggersReverseCompensation() {
        AtomicInteger compensated = new AtomicInteger();
        List<String> compensationOrder = new ArrayList<>();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success(Map.of("a", 1)))
                                .compensator(
                                        ctx -> {
                                            compensated.incrementAndGet();
                                            compensationOrder.add("a");
                                            return CompensationResult.ok("撤销 a");
                                        })
                                .build(),
                        StepDefinition.builder("b", ctx -> StepResult.success(Map.of("b", 2)))
                                .compensator(
                                        ctx -> {
                                            compensated.incrementAndGet();
                                            compensationOrder.add("b");
                                            return CompensationResult.ok("撤销 b");
                                        })
                                .build(),
                        step(
                                "c",
                                ctx -> {
                                    throw new IllegalStateException("下游不可用");
                                }));

        String runId = engine.start(definition, "acme", Map.of());

        WorkflowInstance instance = instance(runId);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.COMPENSATED);
        assertThat(instance.stepStatus("c")).isEqualTo(StepStatus.FAILED);
        assertThat(instance.stepStatus("b")).isEqualTo(StepStatus.COMPENSATED);
        assertThat(instance.stepStatus("a")).isEqualTo(StepStatus.COMPENSATED);
        assertThat(compensated.get()).isEqualTo(2);
        assertThat(compensationOrder).containsExactly("b", "a");
    }

    @Test
    void failureResultTriggersCompensation() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step("a", ctx -> StepResult.success(Map.of("a", 1))),
                        step("b", ctx -> StepResult.failure("风控拒绝")));

        String runId = engine.start(definition, "acme", Map.of());

        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.COMPENSATED);
        assertThat(instance(runId).stepStatus("b")).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void retrySucceedsAfterTransientFailures() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder(
                                        "a",
                                        ctx -> {
                                            int attempt = attempts.incrementAndGet();
                                            if (attempt < 3) {
                                                return StepResult.failure("第 " + attempt + " 次失败");
                                            }
                                            return StepResult.success(Map.of("attempts", attempt));
                                        })
                                .retry(RetryPolicy.fixed(3, Duration.ofMillis(2)))
                                .build());

        String runId = engine.start(definition, "acme", Map.of());

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance(runId).getVariables()).containsEntry("attempts", 3);
    }

    @Test
    void retryExhaustedMarksFailedAndCompensates() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success(Map.of("a", 1)))
                                .compensator(ctx -> CompensationResult.ok("撤销 a"))
                                .build(),
                        StepDefinition.builder(
                                        "b",
                                        ctx -> {
                                            attempts.incrementAndGet();
                                            throw new IllegalStateException("接口 500");
                                        })
                                .retry(RetryPolicy.exponential(3, Duration.ofMillis(1), 2.0d))
                                .build());

        String runId = engine.start(definition, "acme", Map.of());

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.COMPENSATED);
        assertThat(instance(runId).stepStatus("b")).isEqualTo(StepStatus.FAILED);
        assertThat(instance(runId).stepStatus("a")).isEqualTo(StepStatus.COMPENSATED);
    }

    @Test
    void timeoutRetriesThenSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder(
                                        "a",
                                        ctx -> {
                                            int attempt = attempts.incrementAndGet();
                                            if (attempt == 1) {
                                                sleepMillis(400);
                                                return StepResult.failure("第一次超时");
                                            }
                                            return StepResult.success(Map.of("attempt", attempt));
                                        })
                                .timeout(TimeoutPolicy.of(Duration.ofMillis(60)))
                                .retry(RetryPolicy.fixed(3, Duration.ofMillis(1)))
                                .build());

        String runId = engine.start(definition, "acme", Map.of());

        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance(runId).getVariables()).containsEntry("attempt", 2);
    }

    @Test
    void timeoutExhaustedMarksTimedOutAndCompensates() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success(Map.of("a", 1)))
                                .compensator(ctx -> CompensationResult.ok("撤销 a"))
                                .build(),
                        StepDefinition.builder(
                                        "b",
                                        ctx -> {
                                            sleepMillis(300);
                                            return StepResult.success();
                                        })
                                .timeout(TimeoutPolicy.of(Duration.ofMillis(40)))
                                .retry(RetryPolicy.fixed(2, Duration.ofMillis(1)))
                                .build());

        String runId = engine.start(definition, "acme", Map.of());

        assertThat(instance(runId).stepStatus("b")).isEqualTo(StepStatus.TIMED_OUT);
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.COMPENSATED);
        assertThat(instance(runId).stepStatus("a")).isEqualTo(StepStatus.COMPENSATED);
    }

    @Test
    void requiresApprovalSuspendsWithoutRunningHandler() {
        AtomicInteger handlerCalls = new AtomicInteger();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step(
                                "a",
                                ctx -> {
                                    handlerCalls.incrementAndGet();
                                    return StepResult.success();
                                }),
                        StepDefinition.builder(
                                        "b",
                                        ctx -> {
                                            handlerCalls.incrementAndGet();
                                            return StepResult.success();
                                        })
                                .requiresApproval(true)
                                .approvalPrompt("请复核合同金额")
                                .build());

        String runId = engine.start(definition, "acme", Map.of());

        WorkflowInstance instance = instance(runId);
        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(instance.stepStatus("b")).isEqualTo(StepStatus.WAITING_APPROVAL);
        assertThat(instance.getCurrentStepIndex()).isEqualTo(1);
        assertThat(instance.getPendingApproval()).isNotNull();
        assertThat(instance.getPendingApproval().getPrompt()).isEqualTo("请复核合同金额");
        assertThat(instance.getPendingApproval().getStepId()).isEqualTo("b");
        assertThat(engine.pendingApprovals("acme")).hasSize(1);
    }

    @Test
    void approveContinuesRemainingSteps() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step("a", ctx -> StepResult.success(Map.of("a", 1))),
                        StepDefinition.builder("b", ctx -> StepResult.success(Map.of("b", 2)))
                                .requiresApproval(true)
                                .build(),
                        step("c", ctx -> StepResult.success(Map.of("c", 3))));

        String runId = engine.start(definition, "acme", Map.of());
        PendingApproval pending = instance(runId).getPendingApproval();

        engine.approve("acme", pending.getApprovalId(), ApprovalDecision.APPROVE, "同意");

        WorkflowInstance instance = instance(runId);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance.getCurrentStepIndex()).isEqualTo(3);
        assertThat(instance.getPendingApproval()).isNull();
        assertThat(instance.getVariables())
                .containsEntry("a", 1)
                .containsEntry("c", 3)
                .containsEntry("b.approvalComment", "同意");
        assertThat(instance.stepStatus("b")).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(engine.pendingApprovals("acme")).isEmpty();
    }

    @Test
    void rejectTriggersCompensation() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success(Map.of("a", 1)))
                                .compensator(ctx -> CompensationResult.ok("撤销风控记录"))
                                .build(),
                        StepDefinition.builder("b", ctx -> StepResult.success())
                                .requiresApproval(true)
                                .build(),
                        step("c", ctx -> StepResult.success(Map.of("c", 3))));

        String runId = engine.start(definition, "acme", Map.of());
        PendingApproval pending = instance(runId).getPendingApproval();

        engine.approve("acme", pending.getApprovalId(), ApprovalDecision.REJECT, "金额超授权");

        WorkflowInstance instance = instance(runId);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.COMPENSATED);
        assertThat(instance.stepStatus("b")).isEqualTo(StepStatus.FAILED);
        assertThat(instance.stepStatus("a")).isEqualTo(StepStatus.COMPENSATED);
        assertThat(instance.stepStatus("c")).isEqualTo(StepStatus.PENDING);
        assertThat(instance.getVariables()).containsEntry("b.approvalComment", "金额超授权");
    }

    @Test
    void waitApprovalResultSuspendsWithOutput() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step(
                                "a",
                                ctx ->
                                        StepResult.waitApproval(
                                                "金额超阈值，请确认", Map.of("risk", "HIGH"))),
                        step("b", ctx -> StepResult.success(Map.of("notified", true))));

        String runId = engine.start(definition, "acme", Map.of());

        WorkflowInstance instance = instance(runId);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(instance.getVariables()).containsEntry("risk", "HIGH");
        assertThat(instance.getPendingApproval().getPrompt()).isEqualTo("金额超阈值，请确认");

        engine.approve(
                "acme",
                instance.getPendingApproval().getApprovalId(),
                ApprovalDecision.APPROVE,
                null);

        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance(runId).getVariables()).containsEntry("notified", true);
    }

    @Test
    void compensationFailureThrowsAndLeavesFailed() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success())
                                .compensator(ctx -> CompensationResult.failed("风控系统不可用"))
                                .build(),
                        step(
                                "b",
                                ctx -> {
                                    throw new IllegalStateException("失败");
                                }));

        assertThatThrownBy(() -> engine.start(definition, "acme", Map.of()))
                .isInstanceOf(CompensationFailedException.class)
                .hasMessageContaining("风控系统不可用");

        WorkflowInstance instance = store.findAll("acme").get(0);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(instance.stepStatus("a")).isEqualTo(StepStatus.SUCCEEDED);
    }

    @Test
    void compensatorExceptionIsWrapped() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success())
                                .compensator(
                                        ctx -> {
                                            throw new IllegalStateException("撤销接口超时");
                                        })
                                .build(),
                        step("b", ctx -> StepResult.failure("校验不通过")));

        assertThatThrownBy(() -> engine.start(definition, "acme", Map.of()))
                .isInstanceOf(CompensationFailedException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThat(store.findAll("acme").get(0).getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void nullCompensationResultIsTreatedAsFailure() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success())
                                .compensator(ctx -> null)
                                .build(),
                        step("b", ctx -> StepResult.failure("校验不通过")));

        assertThatThrownBy(() -> engine.start(definition, "acme", Map.of()))
                .isInstanceOf(CompensationFailedException.class)
                .hasMessageContaining("补偿器返回 null");
    }

    @Test
    void skippedStepContinuesToNextStep() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step("a", ctx -> StepResult.skipped("无需抽取")),
                        step("b", ctx -> StepResult.success(Map.of("b", 1))));

        String runId = engine.start(definition, "acme", Map.of());

        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance(runId).stepStatus("a")).isEqualTo(StepStatus.SKIPPED);
        assertThat(instance(runId).getVariables()).containsEntry("b", 1);
    }

    @Test
    void snapshotsAreCreatedAroundEachStep() {
        WorkflowDefinition definition =
                workflow("wf", step("a", ctx -> StepResult.success(Map.of("a", 1))));

        String runId = engine.start(definition, "acme", Map.of("seed", 0));

        WorkflowInstance instance = instance(runId);
        assertThat(instance.getSnapshots()).isNotEmpty();
        assertThat(instance.getSnapshots())
                .extracting(RunSnapshot::getVersion)
                .isSorted()
                .startsWith(1);
        assertThat(instance.snapshot(1)).isPresent();
        assertThat(instance.snapshot(1).orElseThrow().getStepIndex()).isZero();
        assertThat(instance.snapshot(1).orElseThrow().getVariables()).containsEntry("seed", 0);
    }

    @Test
    void snapshotLimitTrimsOldSnapshots() {
        WorkflowEngine limited =
                new WorkflowEngine(
                        store, new WorkflowRegistry(), new ToolRegistry(), Clock.systemUTC(), 2);
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step("a", ctx -> StepResult.success(Map.of("a", 1))),
                        step("b", ctx -> StepResult.success(Map.of("b", 2))));

        String runId = limited.start(definition, "acme", Map.of());

        assertThat(limited.find("acme", runId).orElseThrow().getSnapshots()).hasSize(2);
        assertThatThrownBy(
                        () ->
                                new WorkflowEngine(
                                        store,
                                        new WorkflowRegistry(),
                                        new ToolRegistry(),
                                        Clock.systemUTC(),
                                        0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rollbackRestoresVariablesIndexAndStatuses() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step("a", ctx -> StepResult.success(Map.of("a", 1))),
                        step("b", ctx -> StepResult.success(Map.of("b", 2))));

        String runId = engine.start(definition, "acme", Map.of("seed", 0));
        engine.rollbackTo("acme", runId, 1);

        WorkflowInstance instance = instance(runId);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.ROLLED_BACK);
        assertThat(instance.getCurrentStepIndex()).isZero();
        assertThat(instance.getVariables()).containsExactly(Map.entry("seed", 0));
        assertThat(instance.stepStatus("a")).isEqualTo(StepStatus.RUNNING);
        assertThat(instance.stepStatus("b")).isEqualTo(StepStatus.PENDING);
        assertThat(instance.getSnapshots()).extracting(RunSnapshot::getVersion).containsExactly(1);
    }

    @Test
    void rollbackRejectsUnknownSnapshotVersion() {
        WorkflowDefinition definition = workflow("wf", step("a", ctx -> StepResult.success()));
        String runId = engine.start(definition, "acme", Map.of());

        assertThatThrownBy(() -> engine.rollbackTo("acme", runId, 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("快照版本不存在");
    }

    @Test
    void resumeThrowsWhenApprovalPending() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success())
                                .requiresApproval(true)
                                .build());
        String runId = engine.start(definition, "acme", Map.of());

        assertThatThrownBy(() -> engine.resume("acme", runId))
                .isInstanceOf(ApprovalRequiredException.class)
                .hasMessageContaining("等待审批");
    }

    @Test
    void resumeIsNoOpForFinishedRun() {
        AtomicInteger calls = new AtomicInteger();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step(
                                "a",
                                ctx -> {
                                    calls.incrementAndGet();
                                    return StepResult.success();
                                }));
        String runId = engine.start(definition, "acme", Map.of());

        engine.resume("acme", runId);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void resumeContinuesAfterRollback() {
        AtomicInteger calls = new AtomicInteger();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step(
                                "a",
                                ctx -> {
                                    calls.incrementAndGet();
                                    return StepResult.success(Map.of("a", calls.get()));
                                }),
                        step("b", ctx -> StepResult.success(Map.of("b", 1))));
        String runId = engine.start(definition, "acme", Map.of());

        engine.rollbackTo("acme", runId, 1);
        engine.resume("acme", runId);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance(runId).getVariables()).containsEntry("b", 1);
    }

    @Test
    void unknownRunAndApprovalThrowWorkflowNotFound() {
        assertThatThrownBy(() -> engine.resume("acme", "run-missing"))
                .isInstanceOf(WorkflowNotFoundException.class);
        assertThatThrownBy(() -> engine.rollbackTo("acme", "run-missing", 1))
                .isInstanceOf(WorkflowNotFoundException.class);
        assertThatThrownBy(
                        () -> engine.approve("acme", "apr-missing", ApprovalDecision.APPROVE, ""))
                .isInstanceOf(WorkflowNotFoundException.class)
                .hasMessageContaining("未找到审批单");
    }

    @Test
    void approveRejectsBlankApprovalId() {
        assertThatThrownBy(() -> engine.approve("acme", "  ", ApprovalDecision.APPROVE, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approvalId 不可为空");
        assertThatThrownBy(() -> engine.approve("acme", null, ApprovalDecision.APPROVE, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closeReleasesTheStepExecutor() {
        WorkflowDefinition definition = workflow("wf", step("a", ctx -> StepResult.success()));
        engine.start(definition, "acme", Map.of());

        engine.close();

        // 关闭只是释放执行器，已完成的运行仍可查询，不会顺带清掉存储里的数据。
        assertThat(store.findAll("acme")).hasSize(1);
    }

    @Test
    void tenantIsolationAcrossRuns() {
        WorkflowDefinition definition = workflow("wf", step("a", ctx -> StepResult.success()));
        String acmeRun = engine.start(definition, "acme", Map.of());

        assertThat(engine.find("globex", acmeRun)).isEmpty();
        assertThat(store.findAll("acme")).hasSize(1);
        assertThat(store.findAll("globex")).isEmpty();
        assertThat(engine.findByStatus("acme", RunStatus.SUCCEEDED)).hasSize(1);
        assertThat(engine.findByStatus("acme", RunStatus.FAILED)).isEmpty();
    }

    @Test
    void handlerReturningNullIsTreatedAsFailure() {
        WorkflowDefinition definition =
                workflow("wf", step("a", ctx -> StepResult.success()), step("b", ctx -> null));

        String runId = engine.start(definition, "acme", Map.of());

        assertThat(instance(runId).stepStatus("b")).isEqualTo(StepStatus.FAILED);
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.COMPENSATED);
    }

    @Test
    void tenantContextIsVisibleInsideHandler() {
        List<String> seen = new ArrayList<>();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step(
                                "a",
                                ctx -> {
                                    seen.add(AgentflowTenant.current());
                                    return StepResult.success();
                                }));

        engine.start(definition, "acme", Map.of());

        assertThat(seen).containsExactly("acme");
        assertThat(AgentflowTenant.current()).isNull();
    }

    @Test
    void stepsWithoutCompensatorAreLeftUntouched() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step("a", ctx -> StepResult.success(Map.of("a", 1))),
                        step("b", ctx -> StepResult.failure("失败")));

        String runId = engine.start(definition, "acme", Map.of());

        assertThat(instance(runId).stepStatus("a")).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.COMPENSATED);
    }

    @Test
    void approveReadsTenantFromContext() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success(Map.of("a", 1)))
                                .requiresApproval(true)
                                .build(),
                        step("b", ctx -> StepResult.success(Map.of("b", 2))));
        String runId = engine.start(definition, "acme", Map.of());
        String approvalId = instance(runId).getPendingApproval().getApprovalId();

        AgentflowTenant.runWith(
                "acme",
                () -> {
                    engine.approve(approvalId, ApprovalDecision.APPROVE, "通过");
                    engine.rollbackTo(runId, 1);
                });

        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.ROLLED_BACK);
    }
}
