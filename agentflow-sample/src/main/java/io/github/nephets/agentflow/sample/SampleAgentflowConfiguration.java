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
package io.github.nephets.agentflow.sample;

import io.github.nephets.agentflow.core.LlmClient;
import io.github.nephets.agentflow.core.ToolRegistry;
import io.github.nephets.agentflow.core.WorkflowDefinition;
import io.github.nephets.agentflow.core.WorkflowRegistry;
import io.github.nephets.agentflow.starter.AgentflowProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 示例应用装配：注册合同审批工作流与通知工具。 */
@Configuration(proxyBeanMethods = false)
public class SampleAgentflowConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SampleAgentflowConfiguration.class);

    /**
     * 通知工具：注册进工具注册表，供发送通知步骤调用。
     *
     * @param tools 工具注册表
     * @return 通知工具
     */
    @Bean
    public NoticeTool noticeTool(ToolRegistry tools) {
        NoticeTool tool = new NoticeTool();
        log.info("noticeTool 注册通知工具: name={}", NoticeTool.NAME);
        tools.register(tool);
        return tool;
    }

    /**
     * 合同审批工作流：注册进工作流注册表，供 {@code POST /agentflow/runs} 启动。
     *
     * @param registry 工作流注册表
     * @param llm 大模型客户端
     * @param tools 工具注册表
     * @param properties 配置属性
     * @return 工作流定义
     */
    @Bean
    public WorkflowDefinition contractApprovalWorkflow(
            WorkflowRegistry registry,
            LlmClient llm,
            ToolRegistry tools,
            AgentflowProperties properties) {
        WorkflowDefinition definition = ContractApprovalWorkflow.definition(llm, tools, properties);
        log.info("contractApprovalWorkflow 注册工作流: workflowId={}", definition.getWorkflowId());
        registry.register(definition);
        return definition;
    }
}
