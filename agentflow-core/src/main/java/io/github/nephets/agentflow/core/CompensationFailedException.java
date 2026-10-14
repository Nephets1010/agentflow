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

/** 逆序补偿过程中有步骤的补偿器失败或抛异常，整条运行停在 {@link RunStatus#FAILED}。 */
public class CompensationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String stepId;

    /**
     * 构造异常。
     *
     * @param stepId 补偿失败的步骤 id
     * @param message 失败原因
     */
    public CompensationFailedException(String stepId, String message) {
        super("补偿失败：stepId=" + stepId + "，原因=" + message);
        this.stepId = stepId;
    }

    /**
     * 构造异常。
     *
     * @param stepId 补偿失败的步骤 id
     * @param message 失败原因
     * @param cause 原始异常
     */
    public CompensationFailedException(String stepId, String message, Throwable cause) {
        super("补偿失败：stepId=" + stepId + "，原因=" + message, cause);
        this.stepId = stepId;
    }

    /** 返回补偿失败的步骤 id。 */
    public String getStepId() {
        return stepId;
    }
}
