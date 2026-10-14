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
 * 工作流定义注册表：按工作流 id 保存定义，供 {@link WorkflowEngine#resume} 与 HTTP 启动接口查找。
 *
 * <p>同 id 重复注册会覆盖，版本号只做记录，不做多版本共存。
 *
 * <p>注册表是长生命周期容器：工作流 id 可能来自外部输入（例如 HTTP 启动接口按 id 动态注册）， 数量不可控，因此设置了容量上限并按 LRU 淘汰最久未使用的条目。默认上限为
 * {@value #DEFAULT_MAX_CAPACITY}，需要更大规模时用 {@link #WorkflowRegistry(int)} 覆盖。
 */
public final class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    /** 默认容量上限：超出时按 LRU 淘汰最久未使用的条目。 */
    public static final int DEFAULT_MAX_CAPACITY = 1024;

    private final int maxCapacity;
    private final Object lock = new Object();

    /** 访问序 {@link LinkedHashMap}：get/put 都算一次使用，便于 LRU 淘汰。 */
    private final Map<String, WorkflowDefinition> definitions;

    /** 使用默认容量上限（{@value #DEFAULT_MAX_CAPACITY}）构造。 */
    public WorkflowRegistry() {
        this(DEFAULT_MAX_CAPACITY);
    }

    /**
     * 使用指定容量上限构造。
     *
     * @param maxCapacity 容量上限，必须 &gt;= 1；超出时按 LRU 淘汰最久未使用的条目
     * @throws IllegalArgumentException 当 {@code maxCapacity} &lt; 1 时
     */
    public WorkflowRegistry(int maxCapacity) {
        if (maxCapacity < 1) {
            throw new IllegalArgumentException("maxCapacity 必须 >= 1，当前值：" + maxCapacity);
        }
        this.maxCapacity = maxCapacity;
        this.definitions =
                new LinkedHashMap<String, WorkflowDefinition>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<String, WorkflowDefinition> eldest) {
                        if (size() <= WorkflowRegistry.this.maxCapacity) {
                            return false;
                        }
                        log.warn(
                                "register 工作流注册表已满，按 LRU 淘汰最旧条目: workflowId={}, maxCapacity={}",
                                eldest.getKey(),
                                WorkflowRegistry.this.maxCapacity);
                        return true;
                    }
                };
    }

    /**
     * 注册工作流定义。
     *
     * @param definition 定义，不可为 {@code null}
     */
    public void register(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        synchronized (lock) {
            boolean overwrite = definitions.containsKey(definition.getWorkflowId());
            definitions.put(definition.getWorkflowId(), definition);
            if (overwrite) {
                log.info(
                        "register 覆盖已注册工作流: workflowId={}, version={}",
                        definition.getWorkflowId(),
                        definition.getVersion());
            } else {
                log.info(
                        "register 注册工作流: workflowId={}, version={}",
                        definition.getWorkflowId(),
                        definition.getVersion());
            }
        }
    }

    /**
     * 注销工作流定义。
     *
     * @param workflowId 工作流 id
     * @return 确实删掉了返回 {@code true}
     */
    public boolean unregister(String workflowId) {
        if (workflowId == null) {
            return false;
        }
        boolean removed;
        synchronized (lock) {
            removed = definitions.remove(workflowId) != null;
        }
        log.debug("unregister 注销工作流: workflowId={}, removed={}", workflowId, removed);
        return removed;
    }

    /**
     * 查找工作流定义。
     *
     * @param workflowId 工作流 id
     * @return 定义，未注册时返回 {@link Optional#empty()}
     */
    public Optional<WorkflowDefinition> find(String workflowId) {
        if (workflowId == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            return Optional.ofNullable(definitions.get(workflowId));
        }
    }

    /**
     * 查找工作流定义，未注册时抛 {@link IllegalArgumentException}。
     *
     * @param workflowId 工作流 id
     * @return 定义
     */
    public WorkflowDefinition require(String workflowId) {
        return find(workflowId)
                .orElseThrow(
                        () -> {
                            log.error("require 工作流未注册: workflowId={}", workflowId);
                            return new IllegalArgumentException("工作流未注册：" + workflowId);
                        });
    }

    /** 返回已注册的工作流 id，按字典序。 */
    public List<String> ids() {
        List<String> result;
        synchronized (lock) {
            result = new ArrayList<>(definitions.keySet());
        }
        result.sort(String::compareTo);
        return result;
    }

    /** 返回已注册的工作流数量。 */
    public int size() {
        synchronized (lock) {
            return definitions.size();
        }
    }

    /** 返回容量上限，超出时按 LRU 淘汰。 */
    public int getMaxCapacity() {
        return maxCapacity;
    }
}
