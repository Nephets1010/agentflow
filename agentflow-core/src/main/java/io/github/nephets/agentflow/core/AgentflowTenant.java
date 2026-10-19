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

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * 租户上下文：基于 {@link InheritableThreadLocal} 的当前租户持有器。
 *
 * <p>步骤处理器内部可以用 {@link #current()} 读到当前租户； {@link #runWith} / {@link #callWith}
 * 会在结束后恢复原上下文，避免线程池里串租户。
 *
 * <p>线程局部变量一旦写错或忘清理，会表现为 “串租户” 这种难排查的数据越权，因此对入口参数与 上下文缺失都留了日志，便于事后定位是谁在没有租户的情况下发起了调用。
 */
public final class AgentflowTenant {

    private static final Logger log = LoggerFactory.getLogger(AgentflowTenant.class);

    private static final ThreadLocal<String> CURRENT = new InheritableThreadLocal<>();

    private AgentflowTenant() {}

    /**
     * 设置当前线程的租户 id。
     *
     * @param tenantId 租户 id，不可为空
     */
    public static void set(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            log.error("set 租户 id 为空，拒绝写入租户上下文");
            throw new IllegalArgumentException("tenantId 不可为空");
        }
        CURRENT.set(tenantId);
        log.debug("set 写入租户上下文: tenantId={}", tenantId);
    }

    /** 返回当前线程的租户 id；未设置时为 {@code null}。 */
    public static String current() {
        return CURRENT.get();
    }

    /** 当前线程是否已设置租户。 */
    public static boolean isSet() {
        return CURRENT.get() != null;
    }

    /** 清除当前线程的租户。 */
    public static void clear() {
        CURRENT.remove();
        log.debug("clear 清理租户上下文");
    }

    /**
     * 返回当前租户 id，未设置时抛 {@link IllegalStateException}。
     *
     * @return 租户 id
     */
    public static String require() {
        String tenantId = CURRENT.get();
        if (tenantId == null || tenantId.isBlank()) {
            log.error("require 当前线程未设置租户，调用方缺少租户上下文");
            throw new IllegalStateException("当前线程未设置租户，请先调用 AgentflowTenant.set");
        }
        return tenantId;
    }

    /**
     * 在指定租户上下文中执行任务，结束后恢复原上下文。
     *
     * @param tenantId 租户 id
     * @param task 任务，不可为 {@code null}
     */
    public static void runWith(String tenantId, Runnable task) {
        Objects.requireNonNull(task, "task");
        String previous = CURRENT.get();
        set(tenantId);
        try {
            task.run();
        } finally {
            restore(previous);
        }
    }

    /**
     * 在指定租户上下文中执行任务并返回结果，结束后恢复原上下文。
     *
     * @param tenantId 租户 id
     * @param task 任务，不可为 {@code null}
     * @param <T> 返回值类型
     * @return 任务返回值
     * @throws Exception 任务抛出的异常
     */
    public static <T> T callWith(String tenantId, Callable<T> task) throws Exception {
        Objects.requireNonNull(task, "task");
        String previous = CURRENT.get();
        set(tenantId);
        try {
            return task.call();
        } finally {
            restore(previous);
        }
    }

    private static void restore(String previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
