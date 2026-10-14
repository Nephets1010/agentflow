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

import java.time.Instant;
import java.util.Objects;

/** 等待人工确认的检查点。一个运行同一时刻最多挂着一个审批单。 */
public final class PendingApproval {

    private final String approvalId;
    private final String runId;
    private final String stepId;
    private final String prompt;
    private final Instant createdAt;

    private PendingApproval(
            String approvalId, String runId, String stepId, String prompt, Instant createdAt) {
        this.approvalId = approvalId;
        this.runId = runId;
        this.stepId = stepId;
        this.prompt = prompt;
        this.createdAt = createdAt;
    }

    /**
     * 构造审批单。
     *
     * @param approvalId 审批单 id
     * @param runId 运行 id
     * @param stepId 挂起的步骤 id
     * @param prompt 给审批人的提示语
     * @param createdAt 创建时间
     * @return 审批单对象
     */
    public static PendingApproval of(
            String approvalId, String runId, String stepId, String prompt, Instant createdAt) {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stepId, "stepId");
        return new PendingApproval(
                approvalId,
                runId,
                stepId,
                prompt == null ? "" : prompt,
                createdAt == null ? Instant.EPOCH : createdAt);
    }

    /** 返回审批单 id。 */
    public String getApprovalId() {
        return approvalId;
    }

    /** 返回运行 id。 */
    public String getRunId() {
        return runId;
    }

    /** 返回挂起的步骤 id。 */
    public String getStepId() {
        return stepId;
    }

    /** 返回提示语。 */
    public String getPrompt() {
        return prompt;
    }

    /** 返回创建时间。 */
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "PendingApproval{approvalId='"
                + approvalId
                + "', runId='"
                + runId
                + "', stepId='"
                + stepId
                + "', prompt='"
                + prompt
                + "'}";
    }
}
