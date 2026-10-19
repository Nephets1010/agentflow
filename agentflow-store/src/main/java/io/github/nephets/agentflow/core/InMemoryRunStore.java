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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 内存版 {@link RunStore}：按 “租户 id + 运行 id” 存进有界 {@link LinkedHashMap}。
 *
 * <p>保存的是实例引用本身，引擎就地修改后不需要再次 put 也能查到最新状态；进程重启即丢失， 用于测试与单机场景。
 *
 * <p>为避免内存无限增长，本实现设置了容量上限，超出时按 LRU（最近最少使用，即插入/访问顺序） 淘汰最旧的条目。默认上限为 {@value
 * #DEFAULT_MAX_CAPACITY}，可通过构造器覆盖；需要时也可传一个极大值以关闭淘汰。
 */
public final class InMemoryRunStore implements RunStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRunStore.class);

    private static final String KEY_SEPARATOR = "::";

    /** 默认容量上限：超出时淘汰最旧条目。 */
    private static final int DEFAULT_MAX_CAPACITY = 1_000_000;

    private final int maxCapacity;

    private final Map<String, WorkflowInstance> instances;

    /** 使用默认容量上限（{@value #DEFAULT_MAX_CAPACITY}）构造。 */
    public InMemoryRunStore() {
        this(DEFAULT_MAX_CAPACITY);
    }

    /**
     * 使用指定容量上限构造。
     *
     * @param maxCapacity 容量上限，必须 &gt;= 1；超出时按 LRU 淘汰最旧条目
     * @throws IllegalArgumentException 当 {@code maxCapacity} &lt; 1 时
     */
    public InMemoryRunStore(int maxCapacity) {
        if (maxCapacity < 1) {
            throw new IllegalArgumentException("maxCapacity must be >= 1, got: " + maxCapacity);
        }
        this.maxCapacity = maxCapacity;
        this.instances =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<String, WorkflowInstance> eldest) {
                        return size() > InMemoryRunStore.this.maxCapacity;
                    }
                };
    }

    @Override
    public synchronized void save(WorkflowInstance instance) {
        String k = key(instance.getTenantId(), instance.getRunId());
        boolean evicting = !instances.containsKey(k) && instances.size() >= maxCapacity;
        if (evicting) {
            log.info(
                    "save 已达到容量上限，将按 LRU 淘汰最旧条目: tenantId={}, maxCapacity={}",
                    instance.getTenantId(),
                    maxCapacity);
        }
        log.debug("save 保存运行: tenantId={}, runId={}", instance.getTenantId(), instance.getRunId());
        instances.put(k, Objects.requireNonNull(instance, "instance"));
    }

    @Override
    public synchronized Optional<WorkflowInstance> findById(String tenantId, String runId) {
        if (tenantId == null || runId == null) {
            return Optional.empty();
        }
        log.debug("findById 查询运行: tenantId={}, runId={}", tenantId, runId);
        return Optional.ofNullable(instances.get(key(tenantId, runId)));
    }

    @Override
    public synchronized List<PendingApproval> findPendingApprovals(String tenantId) {
        List<PendingApproval> result = new ArrayList<>();
        for (WorkflowInstance instance : ofTenant(tenantId)) {
            PendingApproval pending = instance.getPendingApproval();
            if (pending != null) {
                result.add(pending);
            }
        }
        return result;
    }

    @Override
    public synchronized List<WorkflowInstance> findByStatus(String tenantId, RunStatus status) {
        List<WorkflowInstance> result = new ArrayList<>();
        if (status == null) {
            return result;
        }
        for (WorkflowInstance instance : ofTenant(tenantId)) {
            if (status == instance.getStatus()) {
                result.add(instance);
            }
        }
        return result;
    }

    /**
     * 列出某租户下的全部运行实例。
     *
     * @param tenantId 租户 id
     * @return 运行实例列表
     */
    public synchronized List<WorkflowInstance> findAll(String tenantId) {
        return new ArrayList<>(ofTenant(tenantId));
    }

    /**
     * 取出某租户下的全部运行实例。三个查询方法都要按租户过滤，抽出来避免各自重复一遍遍历与判空。
     *
     * @param tenantId 租户 id，为 {@code null} 时返回空列表
     * @return 该租户的运行实例，保持插入顺序
     */
    private List<WorkflowInstance> ofTenant(String tenantId) {
        List<WorkflowInstance> result = new ArrayList<>();
        if (tenantId == null) {
            return result;
        }
        for (WorkflowInstance instance : instances.values()) {
            if (tenantId.equals(instance.getTenantId())) {
                result.add(instance);
            }
        }
        return result;
    }

    /** 返回已保存的运行实例总数。 */
    public synchronized int size() {
        return instances.size();
    }

    /** 清空全部数据。 */
    public synchronized void clear() {
        instances.clear();
    }

    private static String key(String tenantId, String runId) {
        return tenantId + KEY_SEPARATOR + runId;
    }
}
