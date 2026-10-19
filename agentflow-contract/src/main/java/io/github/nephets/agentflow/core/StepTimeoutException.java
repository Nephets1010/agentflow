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

import java.time.Duration;

/** 单个步骤执行超过了 {@link TimeoutPolicy} 设定的时长。 */
public class StepTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String stepId;
    private final Duration timeout;

    /**
     * 构造异常。
     *
     * @param stepId 超时步骤 id
     * @param timeout 超时阈值
     */
    public StepTimeoutException(String stepId, Duration timeout) {
        super("步骤执行超时：stepId=" + stepId + "，阈值=" + timeout);
        this.stepId = stepId;
        this.timeout = timeout;
    }

    /**
     * 构造异常。
     *
     * @param message 说明
     * @param cause 原因
     */
    public StepTimeoutException(String message, Throwable cause) {
        super(message, cause);
        this.stepId = null;
        this.timeout = Duration.ZERO;
    }

    /** 返回超时步骤 id；通用构造时为 {@code null}。 */
    public String getStepId() {
        return stepId;
    }

    /** 返回超时阈值；通用构造时为 {@link Duration#ZERO}。 */
    public Duration getTimeout() {
        return timeout;
    }
}
