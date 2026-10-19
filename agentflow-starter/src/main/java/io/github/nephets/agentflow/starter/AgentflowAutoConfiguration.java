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

import io.github.nephets.agentflow.core.InMemoryRunStore;
import io.github.nephets.agentflow.core.LlmClient;
import io.github.nephets.agentflow.core.RunStore;
import io.github.nephets.agentflow.core.ToolRegistry;
import io.github.nephets.agentflow.core.WorkflowEngine;
import io.github.nephets.agentflow.core.WorkflowRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * agentflow 自动装配：注册引擎、存储、工具注册表、工作流注册表与本地规则版 {@link LlmClient}。
 *
 * <p>所有 Bean 都带 {@code @ConditionalOnMissingBean}，应用可以用自己的实现整体替换。 Web 相关 Bean（租户过滤器与运行接口）只在 Servlet
 * Web 应用下装配。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentflowProperties.class)
public class AgentflowAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentflowAutoConfiguration.class);

    /**
     * 运行实例存储。
     *
     * @return 内存实现
     */
    @Bean
    @ConditionalOnMissingBean(RunStore.class)
    public RunStore agentflowRunStore() {
        log.info("agentflowRunStore 装配内存运行存储");
        return new InMemoryRunStore();
    }

    /**
     * 工具注册表。
     *
     * @return 空注册表，应用自行注册工具
     */
    @Bean
    @ConditionalOnMissingBean(ToolRegistry.class)
    public ToolRegistry agentflowToolRegistry() {
        log.info("agentflowToolRegistry 装配工具注册表");
        return new ToolRegistry();
    }

    /**
     * 工作流定义注册表。
     *
     * @return 空注册表，应用自行注册工作流
     */
    @Bean
    @ConditionalOnMissingBean(WorkflowRegistry.class)
    public WorkflowRegistry agentflowWorkflowRegistry() {
        log.info("agentflowWorkflowRegistry 装配工作流注册表");
        return new WorkflowRegistry();
    }

    /**
     * 工作流引擎。
     *
     * @param store 运行实例存储
     * @param registry 工作流定义注册表
     * @param tools 工具注册表
     * @param properties 配置属性，提供快照保留上限
     * @return 引擎
     */
    @Bean
    @ConditionalOnMissingBean(WorkflowEngine.class)
    public WorkflowEngine agentflowWorkflowEngine(
            RunStore store,
            WorkflowRegistry registry,
            ToolRegistry tools,
            AgentflowProperties properties) {
        log.info(
                "agentflowWorkflowEngine 装配工作流引擎: snapshotLimit={}", properties.getSnapshotLimit());
        return new WorkflowEngine(
                store, registry, tools, Clock.systemUTC(), properties.getSnapshotLimit());
    }

    /**
     * 大模型客户端。
     *
     * @return 本地规则实现，不联网
     */
    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient agentflowLlmClient() {
        return new RuleBasedLlmClient();
    }

    /** Web 环境下的 Bean：租户过滤器与 {@code /agentflow/runs} 接口。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public static class AgentflowWebConfiguration {

        private static final Logger log = LoggerFactory.getLogger(AgentflowWebConfiguration.class);

        /**
         * 按请求头写入租户上下文的过滤器。
         *
         * @param properties 配置属性
         * @return 过滤器
         */
        @Bean
        @ConditionalOnMissingBean(AgentflowTenantFilter.class)
        public AgentflowTenantFilter agentflowTenantFilter(AgentflowProperties properties) {
            log.info("agentflowTenantFilter 装配租户过滤器");
            return new AgentflowTenantFilter(properties);
        }

        /**
         * 运行接口控制器。
         *
         * @param engine 工作流引擎
         * @param registry 工作流定义注册表
         * @param properties 配置属性
         * @return 控制器
         */
        @Bean
        @ConditionalOnMissingBean(WorkflowController.class)
        public WorkflowController agentflowWorkflowController(
                WorkflowEngine engine, WorkflowRegistry registry, AgentflowProperties properties) {
            log.info("agentflowWorkflowController 装配运行接口控制器");
            return new WorkflowController(engine, registry, properties);
        }
    }
}
