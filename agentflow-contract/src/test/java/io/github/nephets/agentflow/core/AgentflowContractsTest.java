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

import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约层的不变量测试：状态机的终态集合、异常的基类与序列化约定。
 *
 * <p>这些断言守护的是“跨模块公共契约”：任何模块都可以直接依赖这里的枚举与异常， 因此取值集合、继承关系、 serialVersionUID
 * 一旦被无意改动，必须由测试先炸，而不是等使用方在运行时踩坑。
 */
class AgentflowContractsTest {

    /** 运行状态里“不会再自发往下走”的取值：引擎的 resume 对它们直接跳过。 */
    private static final Set<RunStatus> RUN_TERMINAL =
            Set.of(
                    RunStatus.SUCCEEDED,
                    RunStatus.FAILED,
                    RunStatus.COMPENSATED,
                    RunStatus.ROLLED_BACK);

    /** 步骤状态里“不会再被改写”的取值：补偿器只可能把 SUCCEEDED 改写成 COMPENSATED。 */
    private static final Set<StepStatus> STEP_TERMINAL =
            Set.of(
                    StepStatus.SUCCEEDED,
                    StepStatus.FAILED,
                    StepStatus.SKIPPED,
                    StepStatus.TIMED_OUT,
                    StepStatus.COMPENSATED);

    @Test
    void runTerminalSetMatchesTheDocumentedContract() {
        assertThat(
                        Stream.of(RunStatus.values())
                                .filter(RunStatus::isTerminal)
                                .collect(Collectors.toSet()))
                .isEqualTo(RUN_TERMINAL);
        // 瞬态一个都不能混进终态集合：WAITING_APPROVAL 等人工输入，COMPENSATING 正在补偿。
        assertThat(RunStatus.isTerminal(RunStatus.COMPENSATING)).isFalse();
    }

    @Test
    void stepTerminalSetMatchesTheDocumentedContract() {
        assertThat(
                        Stream.of(StepStatus.values())
                                .filter(StepStatus::isTerminal)
                                .collect(Collectors.toSet()))
                .isEqualTo(STEP_TERMINAL);
        assertThat(StepStatus.isTerminal(StepStatus.RUNNING)).isFalse();
        assertThat(StepStatus.isTerminal(StepStatus.WAITING_APPROVAL)).isFalse();
    }

    @Test
    void approvalDecisionHasExactlyApproveAndReject() {
        assertThat(ApprovalDecision.values())
                .containsExactlyInAnyOrder(ApprovalDecision.APPROVE, ApprovalDecision.REJECT);
        assertThat(ApprovalDecision.valueOf("APPROVE")).isEqualTo(ApprovalDecision.APPROVE);
        assertThat(ApprovalDecision.valueOf("REJECT")).isEqualTo(ApprovalDecision.REJECT);
    }

    @Test
    void everyExceptionIsRuntimeAndSerializableWithStableUid() {
        List<Class<?>> contractExceptions =
                Arrays.asList(
                        ApprovalRequiredException.class,
                        CompensationFailedException.class,
                        RetryExhaustedException.class,
                        StepTimeoutException.class,
                        ToolInvocationException.class,
                        ToolNotFoundException.class,
                        WorkflowNotFoundException.class);

        for (Class<?> type : contractExceptions) {
            assertThat(RuntimeException.class.isAssignableFrom(type)).as(type.getName()).isTrue();
            assertThat(Serializable.class.isAssignableFrom(type)).as(type.getName()).isTrue();
            assertThat(declaredSerialVersionUid(type))
                    .as(type.getName() + " 必须显式声明 serialVersionUID")
                    .isEqualTo(1L);
        }
    }

    private static long declaredSerialVersionUid(Class<?> type) {
        try {
            Field field = type.getDeclaredField("serialVersionUID");
            field.setAccessible(true);
            return field.getLong(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError(type.getName() + " 缺少 serialVersionUID", e);
        }
    }
}
