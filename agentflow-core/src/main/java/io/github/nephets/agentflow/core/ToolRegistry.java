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
 * 工具注册表：按名字注册与查找 {@link Tool}，并统一包装调用异常。
 *
 * <p>注册表是长生命周期容器，工具名由业务代码决定、数量不可控，因此设置了容量上限并按 LRU 淘汰最久未使用的条目，避免长期运行时无限增长。默认上限为 {@value
 * #DEFAULT_MAX_CAPACITY}， 需要更大规模时用 {@link #ToolRegistry(int)} 覆盖。
 */
public final class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 默认容量上限：超出时按 LRU 淘汰最久未使用的条目。 */
    public static final int DEFAULT_MAX_CAPACITY = 1024;

    private final int maxCapacity;
    private final Object lock = new Object();

    /** 访问序 {@link LinkedHashMap}：get/put 都算一次使用，便于 LRU 淘汰。 */
    private final Map<String, Tool> tools;

    /** 使用默认容量上限（{@value #DEFAULT_MAX_CAPACITY}）构造。 */
    public ToolRegistry() {
        this(DEFAULT_MAX_CAPACITY);
    }

    /**
     * 使用指定容量上限构造。
     *
     * @param maxCapacity 容量上限，必须 &gt;= 1；超出时按 LRU 淘汰最久未使用的条目
     * @throws IllegalArgumentException 当 {@code maxCapacity} &lt; 1 时
     */
    public ToolRegistry(int maxCapacity) {
        if (maxCapacity < 1) {
            throw new IllegalArgumentException("maxCapacity 必须 >= 1，当前值：" + maxCapacity);
        }
        this.maxCapacity = maxCapacity;
        this.tools =
                new LinkedHashMap<String, Tool>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Tool> eldest) {
                        if (size() <= ToolRegistry.this.maxCapacity) {
                            return false;
                        }
                        log.warn(
                                "register 工具注册表已满，按 LRU 淘汰最旧条目: name={}, maxCapacity={}",
                                eldest.getKey(),
                                ToolRegistry.this.maxCapacity);
                        return true;
                    }
                };
    }

    /**
     * 注册工具，同名会被覆盖。
     *
     * @param tool 工具，不可为 {@code null}，名字不可为空
     */
    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        String name = tool.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名不可为空");
        }
        synchronized (lock) {
            boolean overwrite = tools.containsKey(name);
            tools.put(name, tool);
            if (overwrite) {
                log.info("register 覆盖已注册工具: name={}", name);
            } else {
                log.info("register 注册工具: name={}", name);
            }
        }
    }

    /**
     * 注销工具。
     *
     * @param name 工具名
     * @return 确实删掉了返回 {@code true}
     */
    public boolean unregister(String name) {
        if (name == null) {
            return false;
        }
        boolean removed;
        synchronized (lock) {
            removed = tools.remove(name) != null;
        }
        log.debug("unregister 注销工具: name={}, removed={}", name, removed);
        return removed;
    }

    /**
     * 查找工具。
     *
     * @param name 工具名
     * @return 工具，不存在时返回 {@link Optional#empty()}
     */
    public Optional<Tool> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            return Optional.ofNullable(tools.get(name));
        }
    }

    /**
     * 查找工具，不存在时抛 {@link ToolNotFoundException}。
     *
     * @param name 工具名
     * @return 工具
     */
    public Tool require(String name) {
        return find(name)
                .orElseThrow(
                        () -> {
                            log.error("require 工具未注册: name={}", name);
                            return new ToolNotFoundException(name);
                        });
    }

    /**
     * 调用工具。
     *
     * @param name 工具名
     * @param args 入参，为 {@code null} 时按空表处理
     * @return 调用结果
     * @throws ToolNotFoundException 工具未注册
     * @throws ToolInvocationException 工具内部抛异常
     */
    public ToolResult invoke(String name, Map<String, Object> args) {
        Tool tool = require(name);
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        log.debug("invoke 调用工具: name={}", name);
        try {
            ToolResult result = tool.invoke(safeArgs);
            return result == null ? ToolResult.ok() : result;
        } catch (RuntimeException e) {
            log.error("invoke 工具调用异常: name={}", name, e);
            throw new ToolInvocationException(name, e);
        }
    }

    /** 返回已注册的工具名，按字典序。 */
    public List<String> names() {
        List<String> result;
        synchronized (lock) {
            result = new ArrayList<>(tools.keySet());
        }
        result.sort(String::compareTo);
        return result;
    }

    /** 返回已注册工具数量。 */
    public int size() {
        synchronized (lock) {
            return tools.size();
        }
    }

    /** 返回容量上限，超出时按 LRU 淘汰。 */
    public int getMaxCapacity() {
        return maxCapacity;
    }
}
