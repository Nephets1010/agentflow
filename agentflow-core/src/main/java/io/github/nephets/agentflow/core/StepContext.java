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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 步骤执行上下文：一次步骤调用的只读标识 + 可变的共享变量表。
 *
 * <p>{@code variables} 直接指向所属运行的变量表，步骤里 {@link #put} 的内容对后续步骤可见， 也会进入快照。
 */
public final class StepContext {

    private static final Logger log = LoggerFactory.getLogger(StepContext.class);

    private final String runId;
    private final String tenantId;
    private final String stepId;
    private final int attempt;
    private final Map<String, Object> variables;
    private final Instant startedAt;

    /**
     * 构造上下文。
     *
     * @param runId 运行 id
     * @param tenantId 租户 id
     * @param stepId 步骤 id
     * @param attempt 第几次尝试，从 1 开始
     * @param variables 变量表，直接引用运行实例的变量表
     * @param startedAt 本次尝试的开始时间
     */
    public StepContext(
            String runId,
            String tenantId,
            String stepId,
            int attempt,
            Map<String, Object> variables,
            Instant startedAt) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.tenantId = tenantId;
        this.stepId = Objects.requireNonNull(stepId, "stepId");
        if (attempt < 1) {
            log.error("StepContext 尝试序号非法: stepId={}, attempt={}", stepId, attempt);
            throw new IllegalArgumentException("attempt 必须 >= 1，当前值：" + attempt);
        }
        this.attempt = attempt;
        this.variables = Objects.requireNonNull(variables, "variables");
        this.startedAt = startedAt == null ? Instant.EPOCH : startedAt;
    }

    /** 返回运行 id。 */
    public String getRunId() {
        return runId;
    }

    /** 返回租户 id。 */
    public String getTenantId() {
        return tenantId;
    }

    /** 返回步骤 id。 */
    public String getStepId() {
        return stepId;
    }

    /** 返回当前是第几次尝试，从 1 开始。 */
    public int getAttempt() {
        return attempt;
    }

    /** 返回共享变量表（可修改）。 */
    public Map<String, Object> getVariables() {
        return variables;
    }

    /** 返回本次尝试的开始时间。 */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * 读取变量。
     *
     * @param key 变量名
     * @return 变量值，不存在时为 {@code null}
     */
    public Object get(String key) {
        return variables.get(key);
    }

    /**
     * 写入变量。
     *
     * @param key 变量名
     * @param value 变量值
     * @return 当前上下文，便于链式调用
     */
    public StepContext put(String key, Object value) {
        variables.put(key, value);
        return this;
    }

    /**
     * 读取变量并按期望类型返回。
     *
     * @param key 变量名
     * @param type 期望类型
     * @param <T> 期望类型
     * @return 变量值，不存在或类型不匹配时返回 {@code null}
     */
    public <T> T get(String key, Class<T> type) {
        Object value = variables.get(key);
        if (value == null || !type.isInstance(value)) {
            return null;
        }
        return type.cast(value);
    }
}
