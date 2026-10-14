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

/**
 * 步骤定义：一个步骤怎么执行、失败怎么重试、超时怎么处理、要不要人工确认、怎么补偿。
 *
 * <p>用 {@link #builder()} 构造：
 *
 * <pre>{@code
 * StepDefinition step =
 *     StepDefinition.builder()
 *         .stepId("risk-check")
 *         .name("风控校验")
 *         .handler(ctx -> StepResult.success(Map.of("risk", "LOW")))
 *         .retry(RetryPolicy.fixed(3, Duration.ofMillis(200)))
 *         .timeout(TimeoutPolicy.of(Duration.ofSeconds(5)))
 *         .compensator(ctx -> CompensationResult.ok("已撤销风控记录"))
 *         .build();
 * }</pre>
 */
public final class StepDefinition {

    private static final Logger log = LoggerFactory.getLogger(StepDefinition.class);

    private final String stepId;
    private final String name;
    private final StepHandler handler;
    private final RetryPolicy retry;
    private final TimeoutPolicy timeout;
    private final boolean requiresApproval;
    private final String approvalPrompt;
    private final Compensator compensator;

    private StepDefinition(Builder builder) {
        this.stepId = builder.stepId;
        this.name = builder.name;
        this.handler = builder.handler;
        this.retry = builder.retry;
        this.timeout = builder.timeout;
        this.requiresApproval = builder.requiresApproval;
        this.approvalPrompt = builder.approvalPrompt;
        this.compensator = builder.compensator;
    }

    /** 返回步骤定义构造器。 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回预置了步骤 id 与处理器的构造器。
     *
     * @param stepId 步骤 id
     * @param handler 步骤处理器
     * @return 构造器
     */
    public static Builder builder(String stepId, StepHandler handler) {
        return new Builder().stepId(stepId).handler(handler);
    }

    /** 返回步骤 id。 */
    public String getStepId() {
        return stepId;
    }

    /** 返回步骤名称。 */
    public String getName() {
        return name;
    }

    /** 返回步骤处理器。 */
    public StepHandler getHandler() {
        return handler;
    }

    /** 返回重试策略。 */
    public RetryPolicy getRetry() {
        return retry;
    }

    /** 返回超时策略。 */
    public TimeoutPolicy getTimeout() {
        return timeout;
    }

    /** 是否需要人工确认。 */
    public boolean isRequiresApproval() {
        return requiresApproval;
    }

    /** 返回人工确认的提示语。 */
    public String getApprovalPrompt() {
        return approvalPrompt;
    }

    /** 返回补偿器，未配置时为 {@code null}。 */
    public Compensator getCompensator() {
        return compensator;
    }

    /** 是否配置了补偿器。 */
    public boolean hasCompensator() {
        return compensator != null;
    }

    @Override
    public String toString() {
        return "StepDefinition{stepId='" + stepId + "', name='" + name + "'}";
    }

    /** {@link StepDefinition} 的构造器。 */
    public static final class Builder {

        private String stepId;
        private String name;
        private StepHandler handler;
        private RetryPolicy retry = RetryPolicy.none();
        private TimeoutPolicy timeout = TimeoutPolicy.none();
        private boolean requiresApproval = false;
        private String approvalPrompt;
        private Compensator compensator;

        private Builder() {}

        /**
         * 设置步骤 id。
         *
         * @param stepId 步骤 id，不可为空
         * @return 当前构造器
         */
        public Builder stepId(String stepId) {
            this.stepId = stepId;
            return this;
        }

        /**
         * 设置步骤名称；未设置时回退为步骤 id。
         *
         * @param name 名称
         * @return 当前构造器
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置步骤处理器。
         *
         * @param handler 处理器，不可为 {@code null}
         * @return 当前构造器
         */
        public Builder handler(StepHandler handler) {
            this.handler = handler;
            return this;
        }

        /**
         * 设置重试策略；未设置时为 {@link RetryPolicy#none()}。
         *
         * @param retry 策略
         * @return 当前构造器
         */
        public Builder retry(RetryPolicy retry) {
            this.retry = retry == null ? RetryPolicy.none() : retry;
            return this;
        }

        /**
         * 设置超时策略；未设置时为 {@link TimeoutPolicy#none()}。
         *
         * @param timeout 策略
         * @return 当前构造器
         */
        public Builder timeout(TimeoutPolicy timeout) {
            this.timeout = timeout == null ? TimeoutPolicy.none() : timeout;
            return this;
        }

        /**
         * 设置是否需要人工确认。为 {@code true} 时引擎不执行处理器，直接挂起等人工裁决。
         *
         * @param requiresApproval 是否需要人工确认
         * @return 当前构造器
         */
        public Builder requiresApproval(boolean requiresApproval) {
            this.requiresApproval = requiresApproval;
            return this;
        }

        /**
         * 设置人工确认提示语；未设置时按 “需要人工确认：” + 步骤名称 生成。
         *
         * @param approvalPrompt 提示语
         * @return 当前构造器
         */
        public Builder approvalPrompt(String approvalPrompt) {
            this.approvalPrompt = approvalPrompt;
            return this;
        }

        /**
         * 设置补偿器。
         *
         * @param compensator 补偿器，可为 {@code null}
         * @return 当前构造器
         */
        public Builder compensator(Compensator compensator) {
            this.compensator = compensator;
            return this;
        }

        /**
         * 构造步骤定义。
         *
         * @return 步骤定义
         * @throws IllegalArgumentException stepId 为空或 handler 为 {@code null}
         */
        public StepDefinition build() {
            if (stepId == null || stepId.isBlank()) {
                log.error("build 步骤定义缺少 stepId");
                throw new IllegalArgumentException("stepId 不可为空");
            }
            if (handler == null) {
                // 保持历史行为：缺 handler 抛 NPE，只补一条日志说明是哪一步没配处理器。
                log.error("build 步骤定义缺少处理器: stepId={}", stepId);
            }
            Objects.requireNonNull(handler, "handler");
            String trimmedStepId = stepId.trim();
            String resolvedName = (name == null || name.isBlank()) ? trimmedStepId : name;
            String resolvedPrompt =
                    (approvalPrompt == null || approvalPrompt.isBlank())
                            ? "需要人工确认：" + resolvedName
                            : approvalPrompt;
            Builder filled = new Builder();
            filled.stepId = trimmedStepId;
            filled.name = resolvedName;
            filled.handler = handler;
            filled.retry = retry;
            filled.timeout = timeout;
            filled.requiresApproval = requiresApproval;
            filled.approvalPrompt = resolvedPrompt;
            filled.compensator = compensator;
            StepDefinition built = new StepDefinition(filled);
            log.debug(
                    "build 构造步骤定义: stepId={}, retry={}, timeout={}, requiresApproval={},"
                            + " hasCompensator={}",
                    built.getStepId(),
                    built.getRetry(),
                    built.getTimeout(),
                    built.isRequiresApproval(),
                    built.hasCompensator());
            return built;
        }
    }
}
