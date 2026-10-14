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

import io.github.nephets.agentflow.core.AgentflowTenant;
import io.github.nephets.agentflow.core.ApprovalDecision;
import io.github.nephets.agentflow.core.PendingApproval;
import io.github.nephets.agentflow.core.WorkflowDefinition;
import io.github.nephets.agentflow.core.WorkflowEngine;
import io.github.nephets.agentflow.core.WorkflowInstance;
import io.github.nephets.agentflow.starter.AgentflowProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.Map;

/** 示例应用：跑一遍合同审批的四条路径，并暴露 {@code /agentflow/runs} 接口。 */
@SpringBootApplication
public class AgentflowSampleApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentflowSampleApplication.class);

    /** 演示用的租户。 */
    public static final String TENANT = "acme";

    /**
     * 入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AgentflowSampleApplication.class, args);
    }

    /**
     * 启动后演示四条路径：正常跑完、高风险挂起后批准、拒绝触发补偿、单步超时后重试成功。
     *
     * @param engine 工作流引擎
     * @param contract 合同审批工作流定义
     * @param properties 配置属性
     * @return 启动后执行的演示任务
     */
    @Bean
    public CommandLineRunner agentflowDemo(
            WorkflowEngine engine, WorkflowDefinition contract, AgentflowProperties properties) {
        return args -> {
            log.info("agentflowDemo 启动合同审批示例演示: tenant={}", TENANT);
            log.info("=== agentflow 合同审批示例 ===");
            AgentflowTenant.runWith(
                    TENANT,
                    () -> {
                        happyPath(engine, contract);
                        approvedPath(engine, contract);
                        rejectedPath(engine, contract);
                        timeoutRetryPath(engine, properties);
                    });
            log.info("=== 示例结束，接口见 POST /agentflow/runs ===");
        };
    }

    private static void happyPath(WorkflowEngine engine, WorkflowDefinition contract) {
        String runId = engine.start(contract, TENANT, contractVars("两方采购合同", "ACME", 500_000));
        WorkflowInstance instance = instance(engine, runId);
        log.info(
                "[1] 低风险直接跑完：status={}，risk={}，通知={}",
                instance.getStatus(),
                instance.getVariables().get("risk"),
                instance.getVariables().get("message"));
    }

    private static void approvedPath(WorkflowEngine engine, WorkflowDefinition contract) {
        String runId = engine.start(contract, TENANT, contractVars("大额采购合同", "ACME", 1_500_000));
        PendingApproval pending = instance(engine, runId).getPendingApproval();
        log.info("[2] 高风险挂起：{}", pending == null ? "未挂起" : pending.getPrompt());

        if (pending != null) {
            engine.approve(pending.getApprovalId(), ApprovalDecision.APPROVE, "同意签署");
        }
        WorkflowInstance instance = instance(engine, runId);
        log.info(
                "[2] 批准后继续：status={}，通知={}",
                instance.getStatus(),
                instance.getVariables().get("message"));
    }

    private static void rejectedPath(WorkflowEngine engine, WorkflowDefinition contract) {
        String runId = engine.start(contract, TENANT, contractVars("超额采购合同", "Globex", 2_000_000));
        PendingApproval pending = instance(engine, runId).getPendingApproval();
        if (pending != null) {
            engine.approve(pending.getApprovalId(), ApprovalDecision.REJECT, "超出授权");
        }
        WorkflowInstance instance = instance(engine, runId);
        log.info(
                "[3] 拒绝后补偿：status={}，风控={}，抽取={}",
                instance.getStatus(),
                instance.stepStatus(ContractApprovalWorkflow.STEP_RISK),
                instance.stepStatus(ContractApprovalWorkflow.STEP_EXTRACT));
    }

    private static void timeoutRetryPath(WorkflowEngine engine, AgentflowProperties properties) {
        WorkflowDefinition definition =
                ContractApprovalWorkflow.flakyWorkflow("demo-timeout-retry", 2, 300L, properties);
        String runId = engine.start(definition, TENANT, new LinkedHashMap<>());
        WorkflowInstance instance = instance(engine, runId);
        log.info(
                "[4] 超时后重试：status={}，尝试次数={}",
                instance.getStatus(),
                instance.getVariables().get("attempts"));
    }

    private static Map<String, Object> contractVars(String text, String partyA, int amount) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("contractText", text);
        variables.put("partyA", partyA);
        variables.put("amount", amount);
        return variables;
    }

    private static WorkflowInstance instance(WorkflowEngine engine, String runId) {
        return engine.find(TENANT, runId).orElseThrow();
    }
}
