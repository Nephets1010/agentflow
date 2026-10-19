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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次运行的聚合根：整体状态、当前步骤、每步状态、共享变量、快照与挂起的审批单。
 *
 * <p>由工作流引擎创建并推进，经 {@link RunStore} 持久化。
 *
 * <p><b>为什么快照链只增不删（裁剪除外）：</b>回退是相对“历史版本”的操作，{@link #snapshot(int)} 必须能在任意 时刻回答“第 N
 * 版时长什么样”。版本号一旦被回收或重排，旧审批单、旧运维脚本里记录的版本号就会指错位置， 因此只提供 {@link #dropSnapshotsAfter(int)}（丢弃未来点）与
 * {@link #trimSnapshots(int)}（有界裁剪）， 不提供按版本号删除单点。
 *
 * <p><b>为什么步骤状态表用 {@link LinkedHashMap}：</b>审计与回退都要按定义顺序回放，插入序即步骤定义序， 换 HashMap
 * 会让同一份数据在不同进程里演出不同顺序，排查问题时对不上。
 */
public final class WorkflowInstance {

    private static final Logger log = LoggerFactory.getLogger(WorkflowInstance.class);

    private final String runId;
    private final String tenantId;
    private final String workflowId;
    private final int version;
    private final Instant createdAt;
    private final Map<String, StepStatus> stepStatuses = new LinkedHashMap<>();
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private final List<RunSnapshot> snapshots = new ArrayList<>();

    private RunStatus status;
    private int currentStepIndex;
    private PendingApproval pendingApproval;
    private Instant updatedAt;

    /**
     * 构造运行实例，初始状态为 {@link RunStatus#CREATED}，当前步骤下标为 0。
     *
     * @param runId 运行 id，不可为空
     * @param tenantId 租户 id，不可为空
     * @param workflowId 工作流 id，不可为空
     * @param version 工作流版本号
     * @param createdAt 创建时间，为 {@code null} 时按 {@link Instant#EPOCH} 处理
     */
    public WorkflowInstance(
            String runId, String tenantId, String workflowId, int version, Instant createdAt) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不可为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不可为空");
        }
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId 不可为空");
        }
        this.runId = runId;
        this.tenantId = tenantId;
        this.workflowId = workflowId;
        this.version = version;
        this.createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        this.updatedAt = this.createdAt;
        this.status = RunStatus.CREATED;
        this.currentStepIndex = 0;
    }

    /** 返回运行 id。 */
    public String getRunId() {
        return runId;
    }

    /** 返回租户 id。 */
    public String getTenantId() {
        return tenantId;
    }

    /** 返回工作流 id。 */
    public String getWorkflowId() {
        return workflowId;
    }

    /** 返回工作流版本号。 */
    public int getVersion() {
        return version;
    }

    /** 返回整体状态。 */
    public RunStatus getStatus() {
        return status;
    }

    /**
     * 设置整体状态。
     *
     * @param status 状态，不可为 {@code null}
     */
    public void setStatus(RunStatus status) {
        RunStatus previous = this.status;
        this.status = Objects.requireNonNull(status, "status");
        log.debug("setStatus 状态迁移: runId={}, {} -> {}", runId, previous, status);
    }

    /** 返回当前步骤下标。 */
    public int getCurrentStepIndex() {
        return currentStepIndex;
    }

    /**
     * 设置当前步骤下标。
     *
     * @param currentStepIndex 下标，必须 &gt;= 0
     */
    public void setCurrentStepIndex(int currentStepIndex) {
        if (currentStepIndex < 0) {
            throw new IllegalArgumentException("currentStepIndex 必须 >= 0，当前值：" + currentStepIndex);
        }
        this.currentStepIndex = currentStepIndex;
    }

    /** 返回步骤 id 到状态的映射（不可修改的视图）。 */
    public Map<String, StepStatus> getStepStatuses() {
        return Collections.unmodifiableMap(stepStatuses);
    }

    /**
     * 记录某个步骤的状态。
     *
     * @param stepId 步骤 id
     * @param status 状态
     */
    public void recordStepStatus(String stepId, StepStatus status) {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId 不可为空");
        }
        log.debug("recordStepStatus 步骤状态: runId={}, stepId={}, status={}", runId, stepId, status);
        stepStatuses.put(stepId, Objects.requireNonNull(status, "status"));
    }

    /**
     * 读取某个步骤的状态。
     *
     * @param stepId 步骤 id
     * @return 状态；未记录时返回 {@link StepStatus#PENDING}
     */
    public StepStatus stepStatus(String stepId) {
        return stepStatuses.getOrDefault(stepId, StepStatus.PENDING);
    }

    /**
     * 整体替换步骤状态表，回退时用。
     *
     * @param statuses 新的状态表
     */
    public void replaceStepStatuses(Map<String, StepStatus> statuses) {
        stepStatuses.clear();
        if (statuses != null) {
            stepStatuses.putAll(statuses);
        }
        log.debug("replaceStepStatuses 整体替换步骤状态: runId={}, size={}", runId, stepStatuses.size());
    }

    /** 返回共享变量表（可修改）。 */
    public Map<String, Object> getVariables() {
        return variables;
    }

    /**
     * 写入一个变量。
     *
     * @param key 变量名
     * @param value 变量值
     */
    public void putVariable(String key, Object value) {
        variables.put(key, value);
    }

    /**
     * 批量写入变量。
     *
     * @param values 变量表，为 {@code null} 时不做任何事
     */
    public void putAllVariables(Map<String, Object> values) {
        if (values != null) {
            variables.putAll(values);
        }
    }

    /**
     * 读取一个变量。
     *
     * @param key 变量名
     * @return 变量值，不存在时为 {@code null}
     */
    public Object variable(String key) {
        return variables.get(key);
    }

    /**
     * 整体替换变量表，回退时用。
     *
     * @param values 新的变量表
     */
    public void replaceVariables(Map<String, Object> values) {
        variables.clear();
        if (values != null) {
            variables.putAll(values);
        }
        log.debug("replaceVariables 整体替换变量表: runId={}, size={}", runId, variables.size());
    }

    /** 返回快照列表（不可修改的视图），按版本号升序。 */
    public List<RunSnapshot> getSnapshots() {
        return Collections.unmodifiableList(snapshots);
    }

    /**
     * 追加一份快照。
     *
     * @param snapshot 快照，不可为 {@code null}
     */
    public void addSnapshot(RunSnapshot snapshot) {
        snapshots.add(Objects.requireNonNull(snapshot, "snapshot"));
    }

    /**
     * 按版本号查找快照。
     *
     * @param version 版本号
     * @return 快照，不存在时返回 {@link Optional#empty()}
     */
    public Optional<RunSnapshot> snapshot(int version) {
        for (RunSnapshot snapshot : snapshots) {
            if (snapshot.getVersion() == version) {
                return Optional.of(snapshot);
            }
        }
        return Optional.empty();
    }

    /** 返回最新快照的版本号；还没有快照时返回 0。 */
    public int latestSnapshotVersion() {
        if (snapshots.isEmpty()) {
            return 0;
        }
        return snapshots.get(snapshots.size() - 1).getVersion();
    }

    /**
     * 丢弃版本号大于 {@code version} 的快照，回退后历史未来点不再有效。
     *
     * @param version 保留到的版本号
     */
    public void dropSnapshotsAfter(int version) {
        int before = snapshots.size();
        snapshots.removeIf(snapshot -> snapshot.getVersion() > version);
        log.debug(
                "dropSnapshotsAfter 丢弃未来快照: runId={}, version={}, removed={}",
                runId,
                version,
                before - snapshots.size());
    }

    /**
     * 只保留最近 {@code limit} 份快照。
     *
     * <p>快照每一步生成两份、每份都带一份变量表副本，长流程下是最占内存的部分；裁剪时必须留下 痕迹，否则 “回退时发现早期快照没了” 将无从排查。
     *
     * @param limit 保留上限，小于 1 时不做裁剪
     */
    public void trimSnapshots(int limit) {
        if (limit < 1 || snapshots.size() <= limit) {
            return;
        }
        int removed = snapshots.size() - limit;
        List<RunSnapshot> kept =
                new ArrayList<>(snapshots.subList(snapshots.size() - limit, snapshots.size()));
        snapshots.clear();
        snapshots.addAll(kept);
        log.debug(
                "trimSnapshots 快照超出上限，丢弃最旧的: runId={}, limit={}, removed={}",
                runId,
                limit,
                removed);
    }

    /** 返回挂起的审批单，没有时为 {@code null}。 */
    public PendingApproval getPendingApproval() {
        return pendingApproval;
    }

    /**
     * 设置挂起的审批单。
     *
     * @param pendingApproval 审批单，可为 {@code null} 表示清空
     */
    public void setPendingApproval(PendingApproval pendingApproval) {
        this.pendingApproval = pendingApproval;
    }

    /** 返回创建时间。 */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 返回最后更新时间。 */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 刷新最后更新时间。
     *
     * @param instant 时间，为 {@code null} 时不更新
     */
    public void touch(Instant instant) {
        if (instant != null) {
            this.updatedAt = instant;
        }
    }

    @Override
    public String toString() {
        return "WorkflowInstance{runId='"
                + runId
                + "', tenantId='"
                + tenantId
                + "', workflowId='"
                + workflowId
                + "', status="
                + status
                + ", stepIndex="
                + currentStepIndex
                + '}';
    }
}
