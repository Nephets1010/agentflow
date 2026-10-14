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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 工作流定义：一组按顺序执行的步骤 + 一个版本号。
 *
 * <p>步骤 id 必须唯一，重复时 {@link Builder#build()} 抛 {@link IllegalArgumentException}。
 */
public final class WorkflowDefinition {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinition.class);

    private final String workflowId;
    private final String name;
    private final int version;
    private final List<StepDefinition> steps;

    private WorkflowDefinition(Builder builder) {
        this.workflowId = builder.workflowId;
        this.name = builder.name;
        this.version = builder.version;
        this.steps = List.copyOf(builder.steps);
    }

    /** 返回工作流定义构造器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 返回工作流 id。 */
    public String getWorkflowId() {
        return workflowId;
    }

    /** 返回工作流名称。 */
    public String getName() {
        return name;
    }

    /** 返回版本号。 */
    public int getVersion() {
        return version;
    }

    /** 返回步骤列表（不可修改）。 */
    public List<StepDefinition> getSteps() {
        return steps;
    }

    /** 返回步骤数量。 */
    public int size() {
        return steps.size();
    }

    /**
     * 按步骤 id 查找步骤。
     *
     * @param stepId 步骤 id
     * @return 步骤定义，不存在时返回 {@link Optional#empty()}
     */
    public Optional<StepDefinition> step(String stepId) {
        for (StepDefinition step : steps) {
            if (step.getStepId().equals(stepId)) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "WorkflowDefinition{workflowId='"
                + workflowId
                + "', version="
                + version
                + ", steps="
                + steps.size()
                + '}';
    }

    /** {@link WorkflowDefinition} 的构造器。 */
    public static final class Builder {

        private String workflowId;
        private String name;
        private int version = 1;
        private final List<StepDefinition> steps = new ArrayList<>();

        private Builder() {}

        /**
         * 设置工作流 id。
         *
         * @param workflowId 工作流 id，不可为空
         * @return 当前构造器
         */
        public Builder workflowId(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        /**
         * 设置工作流名称；未设置时回退为工作流 id。
         *
         * @param name 名称
         * @return 当前构造器
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置版本号。
         *
         * @param version 版本号，必须 &gt;= 1
         * @return 当前构造器
         */
        public Builder version(int version) {
            this.version = version;
            return this;
        }

        /**
         * 追加一个步骤。
         *
         * @param step 步骤定义，不可为 {@code null}
         * @return 当前构造器
         */
        public Builder addStep(StepDefinition step) {
            if (step == null) {
                log.error("addStep 步骤为 null: workflowId={}", workflowId);
                throw new IllegalArgumentException("step 不可为 null");
            }
            steps.add(step);
            return this;
        }

        /**
         * 覆盖设置步骤列表。
         *
         * @param steps 步骤列表，不可为 {@code null}
         * @return 当前构造器
         */
        public Builder steps(List<StepDefinition> steps) {
            if (steps == null) {
                log.error("steps 步骤列表为 null: workflowId={}", workflowId);
                throw new IllegalArgumentException("steps 不可为 null");
            }
            this.steps.clear();
            this.steps.addAll(steps);
            return this;
        }

        /**
         * 构造工作流定义。
         *
         * @return 工作流定义
         * @throws IllegalArgumentException workflowId 为空、步骤为空或步骤 id 重复
         */
        public WorkflowDefinition build() {
            if (workflowId == null || workflowId.isBlank()) {
                log.error("build 工作流定义缺少 workflowId");
                throw new IllegalArgumentException("workflowId 不可为空");
            }
            if (steps.isEmpty()) {
                log.error("build 工作流定义没有步骤: workflowId={}", workflowId);
                throw new IllegalArgumentException("工作流至少需要一个步骤");
            }
            if (version < 1) {
                log.error("build 工作流版本号非法: workflowId={}, version={}", workflowId, version);
                throw new IllegalArgumentException("version 必须 >= 1，当前值：" + version);
            }
            Set<String> seen = new LinkedHashSet<>();
            for (StepDefinition step : steps) {
                if (!seen.add(step.getStepId())) {
                    log.error(
                            "build 工作流步骤 id 重复: workflowId={}, stepId={}",
                            workflowId,
                            step.getStepId());
                    throw new IllegalArgumentException("步骤 id 重复：" + step.getStepId());
                }
            }
            Builder filled = new Builder();
            filled.workflowId = workflowId.trim();
            filled.name = (name == null || name.isBlank()) ? filled.workflowId : name;
            filled.version = version;
            filled.steps.addAll(Collections.unmodifiableList(steps));
            WorkflowDefinition built = new WorkflowDefinition(filled);
            log.debug(
                    "build 构造工作流定义: workflowId={}, version={}, stepCount={}",
                    built.getWorkflowId(),
                    built.getVersion(),
                    built.size());
            return built;
        }
    }
}
