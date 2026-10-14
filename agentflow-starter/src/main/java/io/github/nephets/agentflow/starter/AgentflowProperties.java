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
package io.github.nephets.agentflow.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * agentflow 的配置属性，前缀 {@code agentflow}。
 *
 * <p>租户 id 等环境相关取值只在这里出现一次默认值：过滤器与控制器统一走 {@link #DEFAULT_TENANT_ID} 与 {@link
 * #resolveTenantId}，避免同一份兜底值散落在多处、改一处漏一处。
 */
@ConfigurationProperties(prefix = "agentflow")
public class AgentflowProperties {

    private static final Logger log = LoggerFactory.getLogger(AgentflowProperties.class);

    /** 租户 id 的兜底值：配置与请求头都拿不到租户时使用。 */
    public static final String DEFAULT_TENANT_ID = "default";

    /** 租户请求头的兜底名称。 */
    public static final String DEFAULT_TENANT_HEADER = "X-Tenant-Id";

    /** 单步超时的兜底值。 */
    public static final Duration DEFAULT_STEP_TIMEOUT = Duration.ofSeconds(30);

    /** 最大尝试次数的兜底值。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** 快照数量上限的兜底值。 */
    public static final int DEFAULT_SNAPSHOT_LIMIT = 50;

    /** 用于解析租户的请求头名称。默认 {@code X-Tenant-Id}。 */
    private String tenantHeader = DEFAULT_TENANT_HEADER;

    /** 请求头缺失时使用的默认租户 id。默认 {@code default}。 */
    private String defaultTenantId = DEFAULT_TENANT_ID;

    /** 步骤未显式配置超时时的默认单步超时。默认 30s。 */
    private Duration defaultStepTimeout = DEFAULT_STEP_TIMEOUT;

    /** 步骤未显式配置重试时的默认最大尝试次数。默认 3。 */
    private int defaultMaxAttempts = DEFAULT_MAX_ATTEMPTS;

    /** 单个运行保留的快照数量上限。默认 50。 */
    private int snapshotLimit = DEFAULT_SNAPSHOT_LIMIT;

    /**
     * 解析当前请求所属租户：优先用显式传入的租户（通常来自请求头），为空时回退到配置的默认租户， 仍为空时使用 {@link #DEFAULT_TENANT_ID}。
     *
     * <p>过滤器与控制器共用这一段，保证 HTTP 入口的租户判定只有一套规则。
     *
     * @param explicitTenantId 显式租户 id，可为 {@code null} 或空白
     * @param properties 配置属性，提供默认租户 id
     * @return 解析出的租户 id，不会为 {@code null} 或空白
     */
    public static String resolveTenantId(String explicitTenantId, AgentflowProperties properties) {
        if (explicitTenantId != null && !explicitTenantId.isBlank()) {
            return explicitTenantId.trim();
        }
        String fallback = properties == null ? null : properties.getDefaultTenantId();
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return DEFAULT_TENANT_ID;
    }

    /** 返回租户请求头名称。 */
    public String getTenantHeader() {
        return tenantHeader;
    }

    /**
     * 设置租户请求头名称；为 {@code null} 或空白时保留默认值，避免配错后请求头永远匹配不上、 所有请求都静默落到默认租户。
     *
     * @param tenantHeader 请求头名称
     */
    public void setTenantHeader(String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) {
            log.warn(
                    "setTenantHeader 租户请求头为空，回退默认值: value={}, fallback={}",
                    tenantHeader,
                    DEFAULT_TENANT_HEADER);
            this.tenantHeader = DEFAULT_TENANT_HEADER;
            return;
        }
        this.tenantHeader = tenantHeader;
    }

    /** 返回默认租户 id。 */
    public String getDefaultTenantId() {
        return defaultTenantId;
    }

    /**
     * 设置默认租户 id。
     *
     * <p>这里刻意不把空白值 “修正” 成默认值：默认租户写错会让请求静默落到别的租户，把原始值存下来 才能在使用处走兜底并留下日志，而不是在绑定阶段悄悄改写配置。
     *
     * @param defaultTenantId 默认租户 id
     */
    public void setDefaultTenantId(String defaultTenantId) {
        this.defaultTenantId = defaultTenantId;
    }

    /** 返回默认单步超时。 */
    public Duration getDefaultStepTimeout() {
        return defaultStepTimeout;
    }

    /**
     * 设置默认单步超时；为 {@code null} 或负数时保留默认值，否则步骤会立刻超时或构造即报错。
     *
     * @param defaultStepTimeout 超时时长
     */
    public void setDefaultStepTimeout(Duration defaultStepTimeout) {
        if (defaultStepTimeout == null || defaultStepTimeout.isNegative()) {
            log.warn(
                    "setDefaultStepTimeout 默认单步超时非法，回退默认值: value={}, fallback={}",
                    defaultStepTimeout,
                    DEFAULT_STEP_TIMEOUT);
            this.defaultStepTimeout = DEFAULT_STEP_TIMEOUT;
            return;
        }
        this.defaultStepTimeout = defaultStepTimeout;
    }

    /** 返回默认最大尝试次数。 */
    public int getDefaultMaxAttempts() {
        return defaultMaxAttempts;
    }

    /**
     * 设置默认最大尝试次数；小于 1 时保留默认值，{@code WorkflowEngine} 依赖该值构造重试策略。
     *
     * @param defaultMaxAttempts 最大尝试次数
     */
    public void setDefaultMaxAttempts(int defaultMaxAttempts) {
        if (defaultMaxAttempts < 1) {
            log.warn(
                    "setDefaultMaxAttempts 默认最大尝试次数非法，回退默认值: value={}, fallback={}",
                    defaultMaxAttempts,
                    DEFAULT_MAX_ATTEMPTS);
            this.defaultMaxAttempts = DEFAULT_MAX_ATTEMPTS;
            return;
        }
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    /** 返回快照保留上限。 */
    public int getSnapshotLimit() {
        return snapshotLimit;
    }

    /**
     * 设置快照保留上限；小于 1 时保留默认值，{@code WorkflowEngine} 构造时会拒绝小于 1 的上限。
     *
     * @param snapshotLimit 上限值
     */
    public void setSnapshotLimit(int snapshotLimit) {
        if (snapshotLimit < 1) {
            log.warn(
                    "setSnapshotLimit 快照上限非法，回退默认值: value={}, fallback={}",
                    snapshotLimit,
                    DEFAULT_SNAPSHOT_LIMIT);
            this.snapshotLimit = DEFAULT_SNAPSHOT_LIMIT;
            return;
        }
        this.snapshotLimit = snapshotLimit;
    }
}
