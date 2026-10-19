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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单步超时与降级路径：不限时步骤不走执行器、限时步骤跑在虚拟线程上、超时按重试策略降级。
 *
 * <p>虚拟线程语义（在哪条线程上执行、中断怎么传播）是这套超时机制的地基， 这里用 {@code Thread#isVirtual()} 直接断言，而不是靠线程名猜。
 */
class TimeoutDegradationTest {

    /** 测试用的受检错误：验证 Error 不被当成失败重试，而是原样抛出。 */
    static class BoomError extends Error {
        BoomError(String message) {
            super(message);
        }
    }

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
    void unlimitedStepRunsOnTheCallerThread() {
        AtomicBoolean virtual = new AtomicBoolean();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder(
                                        "a",
                                        ctx -> {
                                            virtual.set(Thread.currentThread().isVirtual());
                                            return StepResult.success();
                                        })
                                // 未设置超时即不限时：不占用执行器，调用线程直接跑。
                                .build());

        engine.start(definition, "acme", Map.of());

        assertThat(virtual.get()).isFalse();
    }

    @Test
    void boundedStepRunsOnAVirtualThread() {
        AtomicBoolean virtual = new AtomicBoolean();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder(
                                        "a",
                                        ctx -> {
                                            virtual.set(Thread.currentThread().isVirtual());
                                            return StepResult.success();
                                        })
                                .timeout(TimeoutPolicy.of(Duration.ofSeconds(5)))
                                .build());

        engine.start(definition, "acme", Map.of());

        assertThat(virtual.get()).isTrue();
    }

    @Test
    void timeoutWithoutRetryFailsImmediately() {
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
                                            try {
                                                Thread.sleep(400);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            return StepResult.success();
                                        })
                                .timeout(TimeoutPolicy.of(Duration.ofMillis(40)))
                                .build());

        String runId = engine.start(definition, "acme", Map.of());

        assertThat(attempts.get()).isEqualTo(1);
        assertThat(instance(runId).stepStatus("b")).isEqualTo(StepStatus.TIMED_OUT);
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.COMPENSATED);
        assertThat(instance(runId).stepStatus("a")).isEqualTo(StepStatus.COMPENSATED);
    }

    @Test
    void handlerThrownTimeoutExceptionIsRetriedLikeEngineTimeout() {
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder(
                                        "a",
                                        ctx -> {
                                            throw new StepTimeoutException(
                                                    "a", Duration.ofMillis(30));
                                        })
                                .timeout(TimeoutPolicy.none())
                                .retry(RetryPolicy.fixed(2, Duration.ofMillis(1)))
                                .build());

        String runId = engine.start(definition, "acme", Map.of());

        // 处理器自己抛的超时异常与引擎判超走同一条降级路径：TIMED_OUT 且按策略重试后仍失败。
        assertThat(instance(runId).stepStatus("a")).isEqualTo(StepStatus.TIMED_OUT);
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.COMPENSATED);
    }

    @Test
    void errorFromHandlerPropagatesUnwrapped() {
        AtomicReference<String> seen = new AtomicReference<>();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        step(
                                "a",
                                ctx -> {
                                    seen.set("before");
                                    throw new BoomError("引擎不该吞 Error");
                                }));

        assertThatThrownBy(() -> engine.start(definition, "acme", Map.of()))
                .isInstanceOf(BoomError.class)
                .hasMessageContaining("引擎不该吞 Error");

        // Error 不进重试、不补偿：运行停在失败前的最后一个已落库状态，等人工处理。
        WorkflowInstance instance = store.findAll("acme").get(0);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(instance.stepStatus("a")).isEqualTo(StepStatus.RUNNING);
        assertThat(seen.get()).isEqualTo("before");
    }

    @Test
    void timedOutStepIsVisibleToCompensator() {
        AtomicReference<StepStatus> seenByCompensator = new AtomicReference<>();
        WorkflowDefinition definition =
                workflow(
                        "wf",
                        StepDefinition.builder("a", ctx -> StepResult.success(Map.of("a", 1)))
                                .compensator(
                                        ctx -> {
                                            seenByCompensator.set(
                                                    store.findById("acme", ctx.getRunId())
                                                            .orElseThrow()
                                                            .stepStatus("b"));
                                            return CompensationResult.ok("撤销 a");
                                        })
                                .build(),
                        StepDefinition.builder(
                                        "b",
                                        ctx -> {
                                            try {
                                                Thread.sleep(300);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            return StepResult.success();
                                        })
                                .timeout(TimeoutPolicy.of(Duration.ofMillis(30)))
                                .build());

        String runId = engine.start(definition, "acme", Map.of());

        // 补偿器读取运行时应看到失败步骤已定格为 TIMED_OUT：先记状态再补偿。
        assertThat(seenByCompensator.get()).isEqualTo(StepStatus.TIMED_OUT);
        assertThat(instance(runId).getStatus()).isEqualTo(RunStatus.COMPENSATED);
    }

    private static StepDefinition step(String id, StepHandler handler) {
        return StepDefinition.builder(id, handler).build();
    }
}
