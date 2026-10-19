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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 步骤处理结果：状态 + 输出 + 说明。
 *
 * <p>{@code output} 中的键值对会在步骤成功后合并进 {@link WorkflowInstance} 的变量表，供后续步骤读取； 等待人工确认时也会先合并，便于人工看到业务数据。
 */
public final class StepResult {

    private static final Logger log = LoggerFactory.getLogger(StepResult.class);

    private final StepStatus status;
    private final Map<String, Object> output;
    private final String message;

    private StepResult(StepStatus status, Map<String, Object> output, String message) {
        this.status = status;
        this.output = output;
        this.message = message;
    }

    /**
     * 构造一个成功结果，不带输出。
     *
     * @return 成功结果
     */
    public static StepResult success() {
        return success(Collections.emptyMap());
    }

    /**
     * 构造一个成功结果。
     *
     * @param output 输出变量，为 {@code null} 时按空表处理
     * @return 成功结果
     */
    public static StepResult success(Map<String, Object> output) {
        return new StepResult(StepStatus.SUCCEEDED, copyOf(output), "");
    }

    /**
     * 构造一个失败结果。
     *
     * @param message 失败原因
     * @return 失败结果
     */
    public static StepResult failure(String message) {
        return new StepResult(
                StepStatus.FAILED, new LinkedHashMap<>(), message == null ? "" : message);
    }

    /**
     * 构造一个“挂起等待人工确认”的结果。
     *
     * @param prompt 给审批人的提示语，存放在 {@link #getMessage()} 中
     * @return 等待审批结果
     */
    public static StepResult waitApproval(String prompt) {
        return waitApproval(prompt, Collections.emptyMap());
    }

    /**
     * 构造一个“挂起等待人工确认”的结果，同时带上已产出的输出变量。
     *
     * @param prompt 给审批人的提示语
     * @param output 输出变量，为 {@code null} 时按空表处理
     * @return 等待审批结果
     */
    public static StepResult waitApproval(String prompt, Map<String, Object> output) {
        return new StepResult(
                StepStatus.WAITING_APPROVAL, copyOf(output), prompt == null ? "" : prompt);
    }

    /**
     * 构造一个跳过结果。
     *
     * @param message 跳过原因
     * @return 跳过结果
     */
    public static StepResult skipped(String message) {
        return new StepResult(
                StepStatus.SKIPPED, new LinkedHashMap<>(), message == null ? "" : message);
    }

    /**
     * 按指定状态构造结果，用于 {@code SKIPPED} / {@code COMPENSATED} 等自定义状态。
     *
     * @param status 状态，不可为 {@code null}
     * @param output 输出变量，为 {@code null} 时按空表处理
     * @param message 说明文字，为 {@code null} 时按空串处理
     * @return 对应的结果对象
     */
    public static StepResult of(StepStatus status, Map<String, Object> output, String message) {
        if (status == null) {
            log.error("of 步骤结果缺少状态: message={}", message);
            throw new IllegalArgumentException("status 不可为 null");
        }
        return new StepResult(status, copyOf(output), message == null ? "" : message);
    }

    /** 返回状态。 */
    public StepStatus getStatus() {
        return status;
    }

    /** 返回输出变量（不可修改的副本）。 */
    public Map<String, Object> getOutput() {
        return Collections.unmodifiableMap(output);
    }

    /** 返回说明文字；等待审批时为提示语。 */
    public String getMessage() {
        return message;
    }

    private static Map<String, Object> copyOf(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }
}
