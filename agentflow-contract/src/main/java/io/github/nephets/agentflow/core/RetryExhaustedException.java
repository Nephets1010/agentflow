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

/** 步骤的重试次数已用完仍然失败，引擎会把该步骤记为 {@link StepStatus#FAILED} 并触发补偿。 */
public class RetryExhaustedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String stepId;
    private final int attempts;

    /**
     * 构造异常。
     *
     * @param stepId 步骤 id
     * @param attempts 已尝试次数
     * @param message 最后一次失败的原因
     */
    public RetryExhaustedException(String stepId, int attempts, String message) {
        super("步骤重试已耗尽：stepId=" + stepId + "，尝试次数=" + attempts + "，原因=" + message);
        this.stepId = stepId;
        this.attempts = attempts;
    }

    /**
     * 构造异常。
     *
     * @param stepId 步骤 id
     * @param attempts 已尝试次数
     * @param message 最后一次失败的原因
     * @param cause 最后一次失败的异常
     */
    public RetryExhaustedException(String stepId, int attempts, String message, Throwable cause) {
        super("步骤重试已耗尽：stepId=" + stepId + "，尝试次数=" + attempts + "，原因=" + message, cause);
        this.stepId = stepId;
        this.attempts = attempts;
    }

    /** 返回步骤 id。 */
    public String getStepId() {
        return stepId;
    }

    /** 返回已尝试次数。 */
    public int getAttempts() {
        return attempts;
    }
}
