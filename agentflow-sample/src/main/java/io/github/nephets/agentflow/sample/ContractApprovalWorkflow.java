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

import io.github.nephets.agentflow.core.CompensationResult;
import io.github.nephets.agentflow.core.LlmClient;
import io.github.nephets.agentflow.core.RetryPolicy;
import io.github.nephets.agentflow.core.StepDefinition;
import io.github.nephets.agentflow.core.StepResult;
import io.github.nephets.agentflow.core.TimeoutPolicy;
import io.github.nephets.agentflow.core.ToolRegistry;
import io.github.nephets.agentflow.core.ToolResult;
import io.github.nephets.agentflow.core.WorkflowDefinition;
import io.github.nephets.agentflow.starter.AgentflowProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 合同审批工作流定义：抽取要素 → 风控校验（高风险挂起）→ 发送通知。
 *
 * <p>抽取与风控两步各带补偿器，流程失败或人工拒绝时逆序撤销； 第三步调用 {@link NoticeTool} 发通知。
 */
public final class ContractApprovalWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ContractApprovalWorkflow.class);

    /** 工作流 id。 */
    public static final String WORKFLOW_ID = "contract-approval";

    /** 抽取要素步骤 id。 */
    public static final String STEP_EXTRACT = "extract-terms";

    /** 风控校验步骤 id。 */
    public static final String STEP_RISK = "risk-check";

    /** 发送通知步骤 id。 */
    public static final String STEP_NOTICE = "send-notice";

    /** 金额超过该阈值时风控挂起等待人工确认。 */
    public static final int RISK_THRESHOLD = 1_000_000;

    private ContractApprovalWorkflow() {}

    /**
     * 构造合同审批工作流。
     *
     * @param llm 大模型客户端，用于抽取要素
     * @param tools 工具注册表，通知步骤从中取 {@link NoticeTool}
     * @param properties 配置属性，提供默认超时与重试次数
     * @return 工作流定义
     */
    public static WorkflowDefinition definition(
            LlmClient llm, ToolRegistry tools, AgentflowProperties properties) {
        log.info(
                "definition 构造合同审批工作流: workflowId={}, riskThreshold={}, maxAttempts={},"
                        + " stepTimeout={}",
                WORKFLOW_ID,
                RISK_THRESHOLD,
                properties.getDefaultMaxAttempts(),
                properties.getDefaultStepTimeout());
        return WorkflowDefinition.builder()
                .workflowId(WORKFLOW_ID)
                .name("合同审批")
                .version(1)
                .addStep(
                        StepDefinition.builder(
                                        STEP_EXTRACT,
                                        ctx -> {
                                            String prompt =
                                                    "合同原文："
                                                            + ctx.get("contractText")
                                                            + "\n甲方："
                                                            + ctx.get("partyA")
                                                            + "\n金额："
                                                            + ctx.get("amount");
                                            return StepResult.success(
                                                    Map.of(
                                                            "summary",
                                                            llm.complete(prompt),
                                                            "amount",
                                                            ctx.get("amount")));
                                        })
                                .name("抽取要素")
                                .retry(defaultRetry(properties))
                                .timeout(defaultTimeout(properties))
                                .compensator(ctx -> CompensationResult.ok("已清除要素草稿"))
                                .build())
                .addStep(
                        StepDefinition.builder(
                                        STEP_RISK,
                                        ctx -> {
                                            int amount = ((Number) ctx.get("amount")).intValue();
                                            if (amount > RISK_THRESHOLD) {
                                                return StepResult.waitApproval(
                                                        "合同金额 "
                                                                + amount
                                                                + " 超过阈值 "
                                                                + RISK_THRESHOLD
                                                                + "，请人工确认",
                                                        Map.of("risk", "HIGH"));
                                            }
                                            return StepResult.success(Map.of("risk", "LOW"));
                                        })
                                .name("风控校验")
                                .retry(defaultRetry(properties))
                                .timeout(defaultTimeout(properties))
                                .compensator(ctx -> CompensationResult.ok("已撤销风控记录"))
                                .build())
                .addStep(
                        StepDefinition.builder(
                                        STEP_NOTICE,
                                        ctx -> {
                                            ToolResult result =
                                                    tools.invoke(
                                                            NoticeTool.NAME,
                                                            Map.of(
                                                                    "risk",
                                                                    ctx.get("risk"),
                                                                    "runId",
                                                                    ctx.getRunId()));
                                            if (!result.isOk()) {
                                                return StepResult.failure(
                                                        "通知发送失败：" + result.getError());
                                            }
                                            return StepResult.success(
                                                    Map.of(
                                                            "notified",
                                                            true,
                                                            "message",
                                                            result.getData().get("message")));
                                        })
                                .name("发送通知")
                                .retry(defaultRetry(properties))
                                .timeout(defaultTimeout(properties))
                                .build())
                .build();
    }

    /**
     * 构造一个会先失败若干次的单步工作流，用于演示重试与超时降级。
     *
     * @param workflowId 工作流 id
     * @param failuresBeforeSuccess 成功之前要失败几次
     * @param slowFirstAttemptMillis 第一次尝试要睡多久（用于触发单步超时）
     * @param properties 配置属性
     * @return 工作流定义；{@code counter} 记录实际尝试次数
     */
    public static WorkflowDefinition flakyWorkflow(
            String workflowId,
            int failuresBeforeSuccess,
            long slowFirstAttemptMillis,
            AgentflowProperties properties) {
        AtomicInteger attempts = new AtomicInteger();
        log.info(
                "flakyWorkflow 构造抖动演示工作流: workflowId={}, failuresBeforeSuccess={},"
                        + " slowFirstAttemptMillis={}",
                workflowId,
                failuresBeforeSuccess,
                slowFirstAttemptMillis);
        return WorkflowDefinition.builder()
                .workflowId(workflowId)
                .name("抖动步骤演示")
                .addStep(
                        StepDefinition.builder(
                                        "flaky",
                                        ctx -> {
                                            int attempt = attempts.incrementAndGet();
                                            if (attempt == 1 && slowFirstAttemptMillis > 0) {
                                                sleepQuietly(slowFirstAttemptMillis);
                                            }
                                            if (attempt <= failuresBeforeSuccess) {
                                                return StepResult.failure(
                                                        "第 " + attempt + " 次调用失败");
                                            }
                                            return StepResult.success(Map.of("attempts", attempt));
                                        })
                                .name("调用外部接口")
                                .retry(defaultRetry(properties))
                                .timeout(TimeoutPolicy.of(Duration.ofMillis(80)))
                                .build())
                .build();
    }

    /** 各步骤共用的重试策略：次数取自配置，退避固定 5ms 以免演示跑太久。 */
    private static RetryPolicy defaultRetry(AgentflowProperties properties) {
        return RetryPolicy.fixed(
                Math.max(1, properties.getDefaultMaxAttempts()), Duration.ofMillis(5));
    }

    /** 各步骤共用的超时策略：取自配置的默认单步超时，不在每个步骤里各写一遍构造表达式。 */
    private static TimeoutPolicy defaultTimeout(AgentflowProperties properties) {
        return TimeoutPolicy.of(properties.getDefaultStepTimeout());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // 保留中断标志：调用方（工作流引擎）靠它感知取消，吞掉会让超时策略失效。
            Thread.currentThread().interrupt();
            log.warn("sleepQuietly 等待被中断: millis={}", millis);
        }
    }
}
