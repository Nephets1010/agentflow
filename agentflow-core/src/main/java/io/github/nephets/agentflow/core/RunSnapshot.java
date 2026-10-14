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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 运行快照：回退用的不可变历史点。
 *
 * <p>引擎在每个步骤执行前与执行后各生成一份，{@code version} 从 1 开始自增。
 */
public final class RunSnapshot {

    private static final Logger log = LoggerFactory.getLogger(RunSnapshot.class);

    private final int version;
    private final String runId;
    private final int stepIndex;
    private final StepStatus stepStatus;
    private final Map<String, Object> variables;
    private final Instant createdAt;

    private RunSnapshot(
            int version,
            String runId,
            int stepIndex,
            StepStatus stepStatus,
            Map<String, Object> variables,
            Instant createdAt) {
        this.version = version;
        this.runId = runId;
        this.stepIndex = stepIndex;
        this.stepStatus = stepStatus;
        this.variables = variables;
        this.createdAt = createdAt;
    }

    /**
     * 构造快照。
     *
     * @param version 快照版本号，从 1 开始
     * @param runId 运行 id
     * @param stepIndex 当前步骤下标
     * @param stepStatus 当前步骤状态
     * @param variables 变量表副本，为 {@code null} 时按空表处理
     * @param createdAt 创建时间，为 {@code null} 时按 {@link Instant#EPOCH} 处理
     * @return 快照对象
     */
    public static RunSnapshot of(
            int version,
            String runId,
            int stepIndex,
            StepStatus stepStatus,
            Map<String, Object> variables,
            Instant createdAt) {
        if (version < 1) {
            log.error("of 快照版本号非法: runId={}, version={}", runId, version);
            throw new IllegalArgumentException("version 必须 >= 1，当前值：" + version);
        }
        if (stepStatus == null) {
            log.error("of 快照缺少步骤状态: runId={}, version={}", runId, version);
        }
        Objects.requireNonNull(stepStatus, "stepStatus");
        Map<String, Object> copy =
                variables == null ? new LinkedHashMap<>() : new LinkedHashMap<>(variables);
        return new RunSnapshot(
                version,
                runId,
                stepIndex,
                stepStatus,
                copy,
                createdAt == null ? Instant.EPOCH : createdAt);
    }

    /** 返回快照版本号。 */
    public int getVersion() {
        return version;
    }

    /** 返回运行 id。 */
    public String getRunId() {
        return runId;
    }

    /** 返回当前步骤下标。 */
    public int getStepIndex() {
        return stepIndex;
    }

    /** 返回当前步骤状态。 */
    public StepStatus getStepStatus() {
        return stepStatus;
    }

    /** 返回变量表副本（不可修改）。 */
    public Map<String, Object> getVariables() {
        return Collections.unmodifiableMap(variables);
    }

    /** 返回创建时间。 */
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "RunSnapshot{version="
                + version
                + ", runId='"
                + runId
                + "', stepIndex="
                + stepIndex
                + ", stepStatus="
                + stepStatus
                + '}';
    }
}
