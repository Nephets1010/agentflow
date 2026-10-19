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

import java.util.List;
import java.util.Optional;

/**
 * 运行实例存储（SPI）。所有读写都带租户 id，实现必须保证跨租户不可见。
 *
 * <p>默认实现见 {@link InMemoryRunStore}。
 */
public interface RunStore {

    /**
     * 保存（新增或覆盖）运行实例。
     *
     * @param instance 运行实例，不可为 {@code null}
     */
    void save(WorkflowInstance instance);

    /**
     * 按租户 + 运行 id 查找。
     *
     * @param tenantId 租户 id
     * @param runId 运行 id
     * @return 运行实例，不存在时返回 {@link Optional#empty()}
     */
    Optional<WorkflowInstance> findById(String tenantId, String runId);

    /**
     * 列出某租户下所有等待人工确认的审批单。
     *
     * @param tenantId 租户 id
     * @return 审批单列表，无结果时返回空列表
     */
    List<PendingApproval> findPendingApprovals(String tenantId);

    /**
     * 按状态列出某租户下的运行实例。
     *
     * @param tenantId 租户 id
     * @param status 运行状态
     * @return 运行实例列表，无结果时返回空列表
     */
    List<WorkflowInstance> findByStatus(String tenantId, RunStatus status);
}
