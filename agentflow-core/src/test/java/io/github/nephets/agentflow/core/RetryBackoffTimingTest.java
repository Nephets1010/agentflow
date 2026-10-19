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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重试与退避的时序测试：退避序列由注入的 {@link WorkflowEngine.Sleeper} 记录，不依赖真实睡眠。
 *
 * <p>重试策略的核心语义是“第 N 次失败后等多久”，用墙钟断言只会得到一坨抖动数据； 换成记录器后可以直接对序列本身 下断言，测试既快又稳。
 */
class RetryBackoffTimingTest {

    private final List<Duration> slept = Collections.synchronizedList(new ArrayList<>());

    private InMemoryRunStore store;
    private WorkflowEngine engine;

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

    /** 记录退避时长而不是真的睡；需要时也可以主动抛中断。 */
    private final WorkflowEngine.Sleeper recorder =
            duration -> {
                slept.add(duration);
            };

    @BeforeEach
    void setUp() {
        AgentflowTenant.clear();
        slept.clear();
        store = new InMemoryRunStore();
        engine =
                new WorkflowEngine(
                        store,
                        new WorkflowRegistry(),
                        new ToolRegistry(),
                        java.time.Clock.systemUTC(),
                        50,
                        recorder);
    }

    @AfterEach
    void tearDown() {
        engine.close();
        AgentflowTenant.clear();
    }

    @Test
    void fixedBackoffSleepsTheSameDurationOnEveryRetry() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder(
                                        "a",
                                        ctx -> {
                                            attempts.incrementAndGet();
                                            return StepResult.failure("一直失败");
                                        })
                                .retry(RetryPolicy.fixed(3, Duration.ofMillis(20)))
                                .build());

        String runId = engine.start(definition, "acme", Map.of());

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(slept).containsExactly(Duration.ofMillis(20), Duration.ofMillis(20));
        assertThat(instance(runId).stepStatus("a")).isEqualTo(StepStatus.FAILED);
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.COMPENSATED);
    }

    @Test
    void exponentialBackoffGrowsOnEveryRetry() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder(
                                        "a",
                                        ctx -> {
                                            attempts.incrementAndGet();
                                            return StepResult.failure("一直失败");
                                        })
                                .retry(RetryPolicy.exponential(4, Duration.ofMillis(10), 2.0d))
                                .build());

        engine.start(definition, "acme", Map.of());

        assertThat(attempts.get()).isEqualTo(4);
        assertThat(slept)
                .containsExactly(
                        Duration.ofMillis(10), Duration.ofMillis(20), Duration.ofMillis(40));
    }

    @Test
    void noRetryPolicyNeverSleeps() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder(
                                        "a",
                                        ctx -> {
                                            attempts.incrementAndGet();
                                            return StepResult.failure("一次就挂");
                                        })
                                .build());

        engine.start(definition, "acme", Map.of());

        assertThat(attempts.get()).isEqualTo(1);
        assertThat(slept).isEmpty();
    }

    @Test
    void zeroBackoffNeverSleeps() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder(
                                        "a",
                                        ctx -> {
                                            attempts.incrementAndGet();
                                            return StepResult.failure("一直失败");
                                        })
                                .retry(RetryPolicy.fixed(3, Duration.ZERO))
                                .build());

        engine.start(definition, "acme", Map.of());

        assertThat(attempts.get()).isEqualTo(3);
        // 零退避直接进入下一次尝试：连等待调用都不该发生。
        assertThat(slept).isEmpty();
    }

    @Test
    void backoffStopsAsSoonAsRetrySucceeds() {
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
                                .retry(RetryPolicy.fixed(5, Duration.ofMillis(7)))
                                .build());

        String runId = engine.start(definition, "acme", Map.of());

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(slept).containsExactly(Duration.ofMillis(7), Duration.ofMillis(7));
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void timeoutRetryAlsoConsumesTheBackoffBudget() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder(
                                        "a",
                                        ctx -> {
                                            int attempt = attempts.incrementAndGet();
                                            if (attempt == 1) {
                                                try {
                                                    Thread.sleep(500);
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                            }
                                            return StepResult.success(Map.of("attempt", attempt));
                                        })
                                .timeout(TimeoutPolicy.of(Duration.ofMillis(50)))
                                .retry(RetryPolicy.fixed(2, Duration.ofMillis(15)))
                                .build());

        String runId = engine.start(definition, "acme", Map.of());

        // 超时按普通失败走同一条退避：第一次超时后也要等 15ms 再试。
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(slept).containsExactly(Duration.ofMillis(15));
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(instance(runId).getVariables()).containsEntry("attempt", 2);
    }

    @Test
    void interruptedBackoffFailsTheStepAndRestoresInterruptFlag() {
        WorkflowEngine.Sleeper interrupting =
                duration -> {
                    throw new InterruptedException("演练中断");
                };
        WorkflowEngine interruptingEngine =
                new WorkflowEngine(
                        store,
                        new WorkflowRegistry(),
                        new ToolRegistry(),
                        java.time.Clock.systemUTC(),
                        50,
                        interrupting);
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.failure("一直失败"))
                                .retry(RetryPolicy.fixed(3, Duration.ofMillis(5)))
                                .build());

        try {
            String runId = interruptingEngine.start(definition, "acme", Map.of());

            // 退避被中断不能当成普通失败重试：中断位恢复后步骤记失败并走补偿。
            assertThat(instance(runId).stepStatus("a")).isEqualTo(StepStatus.FAILED);
            assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.COMPENSATED);
            assertThat(Thread.interrupted()).isTrue();
        } finally {
            interruptingEngine.close();
        }
    }

    @Test
    void attemptNumberGrowsInStepContext() {
        List<Integer> seenAttempts = Collections.synchronizedList(new ArrayList<>());
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder(
                                        "a",
                                        ctx -> {
                                            seenAttempts.add(ctx.getAttempt());
                                            return StepResult.failure("一直失败");
                                        })
                                .retry(RetryPolicy.fixed(3, Duration.ofMillis(3)))
                                .build());

        engine.start(definition, "acme", Map.of());

        assertThat(seenAttempts).containsExactly(1, 2, 3);
    }

    @Test
    void publicConstructorDelegatesToSystemSleep() {
        // 公开构造器不接 Sleeper：确认默认路径仍可构造并跑完（系统睡眠在零退避下不会被触发）。
        WorkflowEngine defaultEngine = new WorkflowEngine(new InMemoryRunStore());
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder(
                                        "a",
                                        ctx -> {
                                            if (ctx.getAttempt() < 2) {
                                                return StepResult.failure("第一次失败");
                                            }
                                            return StepResult.success();
                                        })
                                .retry(RetryPolicy.fixed(3, Duration.ofMillis(1)))
                                .build());

        String runId = defaultEngine.start(definition, "acme", Map.of());

        assertThat(defaultEngine.find("acme", runId).orElseThrow().getStatus())
                .isEqualTo(RunStatus.SUCCEEDED);
        defaultEngine.close();
        assertThat(engine.find("acme", "run-missing")).isEmpty();
    }
}
