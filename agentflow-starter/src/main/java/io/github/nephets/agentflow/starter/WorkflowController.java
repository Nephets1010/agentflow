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

import io.github.nephets.agentflow.core.AgentflowTenant;
import io.github.nephets.agentflow.core.ApprovalDecision;
import io.github.nephets.agentflow.core.CompensationFailedException;
import io.github.nephets.agentflow.core.PendingApproval;
import io.github.nephets.agentflow.core.RunSnapshot;
import io.github.nephets.agentflow.core.WorkflowDefinition;
import io.github.nephets.agentflow.core.WorkflowEngine;
import io.github.nephets.agentflow.core.WorkflowInstance;
import io.github.nephets.agentflow.core.WorkflowNotFoundException;
import io.github.nephets.agentflow.core.WorkflowRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * agentflow 的运行接口：启动、查询、审批与回退。
 *
 * <p>租户 id 取自 {@link AgentflowTenant}（由 {@link AgentflowTenantFilter} 按请求头写入）， 缺失时回退到 {@code
 * agentflow.default-tenant-id}。
 */
@RestController
@RequestMapping("/agentflow/runs")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowEngine engine;
    private final WorkflowRegistry registry;
    private final AgentflowProperties properties;

    /**
     * 构造控制器。
     *
     * @param engine 工作流引擎
     * @param registry 工作流定义注册表
     * @param properties 配置属性
     */
    public WorkflowController(
            WorkflowEngine engine, WorkflowRegistry registry, AgentflowProperties properties) {
        this.engine = engine;
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * 启动一次运行。请求体：{@code {"workflowId": "...", "variables": {...}}}。
     *
     * @param body 请求体
     * @return 运行视图；工作流未注册返回 404，缺少 workflowId 返回 400
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> start(
            @RequestBody(required = false) Map<String, Object> body) {
        String workflowId = text(body, "workflowId");
        log.info("start 收到启动请求: workflowId={}", workflowId);
        if (workflowId == null || workflowId.isBlank()) {
            log.warn("start 请求体缺少 workflowId: body={}", body == null ? "null" : "non-null");
            return badRequest("缺少 workflowId");
        }
        Optional<WorkflowDefinition> definition = registry.find(workflowId.trim());
        if (definition.isEmpty()) {
            log.warn("start 工作流未注册: workflowId={}", workflowId);
            return notFound("工作流未注册：" + workflowId);
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        Object raw = body == null ? null : body.get("variables");
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> variables.put(String.valueOf(key), value));
        }
        String tenantId = tenant();
        String runId = engine.start(definition.orElseThrow(), tenantId, variables);
        log.info("start 运行已启动: tenantId={}, workflowId={}, runId={}", tenantId, workflowId, runId);
        return ok(describe(engine.find(tenantId, runId).orElseThrow()));
    }

    /**
     * 查询一次运行的状态、变量与快照版本。
     *
     * @param runId 运行 id
     * @return 运行视图；不存在返回 404
     */
    @GetMapping("/{runId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String runId) {
        String tenantId = tenant();
        log.info("get 查询运行: tenantId={}, runId={}", tenantId, runId);
        Optional<WorkflowInstance> found = engine.find(tenantId, runId);
        if (found.isEmpty()) {
            log.warn("get 运行不存在: tenantId={}, runId={}", tenantId, runId);
            return notFound("运行不存在：" + runId);
        }
        return ok(describe(found.orElseThrow()));
    }

    /**
     * 对挂起的审批单给出裁决。请求体：{@code {"approvalId": "...", "decision": "APPROVE|REJECT", "comment":
     * "..."}}； 省略 approvalId 时使用该运行当前的审批单。
     *
     * @param runId 运行 id
     * @param body 请求体
     * @return 运行视图
     */
    @PostMapping("/{runId}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable String runId, @RequestBody(required = false) Map<String, Object> body) {
        String tenantId = tenant();
        log.info("approve 收到审批请求: tenantId={}, runId={}", tenantId, runId);
        WorkflowInstance instance = engine.find(tenantId, runId).orElse(null);
        if (instance == null) {
            log.warn("approve 运行不存在: tenantId={}, runId={}", tenantId, runId);
            return notFound("运行不存在：" + runId);
        }
        String rawApprovalId = text(body, "approvalId");
        String approvalId = rawApprovalId;
        if (rawApprovalId == null || rawApprovalId.isBlank()) {
            PendingApproval pending = instance.getPendingApproval();
            if (pending == null) {
                log.warn("approve 运行没有挂起审批单: tenantId={}, runId={}", tenantId, runId);
                return badRequest("运行 " + runId + " 当前没有挂起的审批单");
            }
            approvalId = pending.getApprovalId();
        }
        if (approvalId == null || approvalId.isBlank()) {
            log.warn("approve 无法解析审批单 id: tenantId={}, runId={}", tenantId, runId);
            return badRequest("缺少 approvalId");
        }
        String decisionText = text(body, "decision");
        ApprovalDecision decision;
        if (decisionText != null && "reject".equalsIgnoreCase(decisionText.trim())) {
            decision = ApprovalDecision.REJECT;
        } else if (decisionText != null && "approve".equalsIgnoreCase(decisionText.trim())) {
            decision = ApprovalDecision.APPROVE;
        } else {
            return badRequest("decision 只能是 APPROVE 或 REJECT");
        }
        String finalApprovalId = approvalId;
        String comment = text(body, "comment");
        try {
            AgentflowTenant.runWith(
                    tenantId, () -> engine.approve(finalApprovalId, decision, comment));
        } catch (WorkflowNotFoundException e) {
            log.error("approve 运行未找到: runId={}", runId, e);
            return notFound(e.getMessage());
        } catch (CompensationFailedException e) {
            log.error("approve 补偿失败: runId={}, stepId={}", runId, e.getStepId(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "补偿失败：" + e.getMessage());
            error.put("stepId", e.getStepId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        } catch (IllegalArgumentException e) {
            // 审批单 id 等入参非法属于调用方错误，映射成 400，而不是让它冒泡成 500。
            log.warn("approve 审批入参非法: tenantId={}, runId={}", tenantId, runId, e);
            return badRequest(e.getMessage());
        }
        return ok(describe(engine.find(tenantId, runId).orElseThrow()));
    }

    /**
     * 回退到指定快照版本。
     *
     * @param runId 运行 id
     * @param version 快照版本号
     * @return 运行视图
     */
    @PostMapping("/{runId}/rollback")
    public ResponseEntity<Map<String, Object>> rollback(
            @PathVariable String runId, @RequestParam("version") int version) {
        String tenantId = tenant();
        log.info("rollback 收到回退请求: tenantId={}, runId={}, version={}", tenantId, runId, version);
        if (engine.find(tenantId, runId).isEmpty()) {
            log.warn("rollback 运行不存在: tenantId={}, runId={}", tenantId, runId);
            return notFound("运行不存在：" + runId);
        }
        try {
            AgentflowTenant.runWith(tenantId, () -> engine.rollbackTo(runId, version));
        } catch (IllegalArgumentException e) {
            log.warn(
                    "rollback 参数非法: runId={}, version={}, message={}",
                    runId,
                    version,
                    e.getMessage());
            return badRequest(e.getMessage());
        }
        return ok(describe(engine.find(tenantId, runId).orElseThrow()));
    }

    private Map<String, Object> describe(WorkflowInstance instance) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", instance.getRunId());
        view.put("tenantId", instance.getTenantId());
        view.put("workflowId", instance.getWorkflowId());
        view.put("version", instance.getVersion());
        view.put("status", instance.getStatus().name());
        view.put("currentStepIndex", instance.getCurrentStepIndex());
        view.put("stepStatuses", new LinkedHashMap<>(namedStatuses(instance)));
        view.put("variables", new LinkedHashMap<>(instance.getVariables()));
        view.put("snapshotVersions", snapshotVersions(instance));
        PendingApproval pending = instance.getPendingApproval();
        if (pending != null) {
            Map<String, Object> approval = new LinkedHashMap<>();
            approval.put("approvalId", pending.getApprovalId());
            approval.put("stepId", pending.getStepId());
            approval.put("prompt", pending.getPrompt());
            view.put("pendingApproval", approval);
        } else {
            view.put("pendingApproval", null);
        }
        return view;
    }

    private static Map<String, String> namedStatuses(WorkflowInstance instance) {
        Map<String, String> statuses = new LinkedHashMap<>();
        instance.getStepStatuses().forEach((stepId, status) -> statuses.put(stepId, status.name()));
        return statuses;
    }

    private static List<Integer> snapshotVersions(WorkflowInstance instance) {
        List<Integer> versions = new ArrayList<>();
        for (RunSnapshot snapshot : instance.getSnapshots()) {
            versions.add(snapshot.getVersion());
        }
        return versions;
    }

    private String tenant() {
        String tenantId =
                AgentflowProperties.resolveTenantId(AgentflowTenant.current(), properties);
        log.debug("tenant 解析租户: tenantId={}", tenantId);
        return tenantId;
    }

    private static String text(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", message));
    }
}
