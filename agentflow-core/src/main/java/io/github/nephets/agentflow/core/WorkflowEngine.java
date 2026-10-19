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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工作流引擎：按步骤定义顺序推进一次运行，负责重试退避、单步超时、人工确认挂起、失败逆序补偿与快照回退。
 *
 * <p>执行语义：
 *
 * <ul>
 *   <li>步骤抛异常或返回 {@link StepResult#failure} 会消耗一次重试预算，预算内按 {@link RetryPolicy} 退避后重跑；
 *   <li>超过 {@link TimeoutPolicy} 的步骤记为 {@link StepStatus#TIMED_OUT}，同样按重试策略处理；
 *   <li>步骤声明 {@code requiresApproval} 或处理器返回 {@link StepResult#waitApproval} 时挂起，生成 {@link
 *       PendingApproval} 并把运行置为 {@link RunStatus#WAITING_APPROVAL}；
 *   <li>步骤最终失败时，逆序对状态为 {@link StepStatus#SUCCEEDED} 的步骤执行补偿器，全部成功则 {@link
 *       RunStatus#COMPENSATED}，任一失败抛 {@link CompensationFailedException}；
 *   <li>每个步骤执行前后各生成一份 {@link RunSnapshot}，可用 {@link #rollbackTo} 回到某个快照版本。
 * </ul>
 *
 * <p>{@link #resume}、{@link #approve}、{@link #rollbackTo} 的无租户参数版本从 {@link AgentflowTenant} 取租户。
 *
 * <p>引擎内部持有一个虚拟线程执行器用于单步超时，随引擎一起长期存活；不再使用时应调用 {@link #close()} 释放，交由 Spring 管理时容器关闭会自动调用。
 *
 * <p><b>设计约束（为什么）：</b>
 *
 * <ul>
 *   <li>状态迁移只走 {@link WorkflowInstance#setStatus} / {@link #recordStepStatus} 一条路，且每个迁移前先落存储：
 *       运行是跨请求的长生命周期对象，任何“先改内存后补存”的写法都会在进程重启后丢状态；
 *   <li>{@link Clock} 由构造器注入：快照时间、审批单时间、更新时间全部走它，测试可以固定时间断言版本链， 不必依赖真实时钟；
 *   <li>退避等待走可注入的 {@link Sleeper}（默认系统睡眠）：重试策略的核心语义是“失败后等多久再试”， 直接 {@link Thread#sleep}
 *       会让测试要么真睡、要么只能用极小时长蒙过去，抽成注入点后才能对退避序列本身断言；
 *   <li>快照保留完整版本链：回退是相对历史版本的操作，版本号被回收或重排会让旧引用指错位置，因此只淘汰最旧、 只丢弃未来点；
 *   <li>观测入口（{@link #find}、{@link #pendingApprovals}、{@link #findByStatus}）只读存储且对 {@code null}
 *       入参返回空结果：监控与运维脚本不应因为一次手滑查询把引擎打挂。
 * </ul>
 */
public final class WorkflowEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private static final String RUN_PREFIX = "run-";
    private static final String APPROVAL_PREFIX = "apr-";
    private static final String COMMENT_SUFFIX = ".approvalComment";
    private static final int DEFAULT_SNAPSHOT_LIMIT = 50;

    private final RunStore store;
    private final WorkflowRegistry registry;
    private final ToolRegistry tools;
    private final Clock clock;
    private final int snapshotLimit;
    private final ExecutorService executor;
    private final Sleeper sleeper;

    /**
     * 用内存存储与默认配置构造引擎。
     *
     * @param store 运行实例存储
     */
    public WorkflowEngine(RunStore store) {
        this(
                store,
                new WorkflowRegistry(),
                new ToolRegistry(),
                Clock.systemUTC(),
                DEFAULT_SNAPSHOT_LIMIT);
    }

    /**
     * 构造引擎。
     *
     * @param store 运行实例存储
     * @param registry 工作流定义注册表
     * @param tools 工具注册表
     * @param clock 时钟，便于测试固定时间
     * @param snapshotLimit 单个运行保留的快照上限
     */
    public WorkflowEngine(
            RunStore store,
            WorkflowRegistry registry,
            ToolRegistry tools,
            Clock clock,
            int snapshotLimit) {
        this(store, registry, tools, clock, snapshotLimit, WorkflowEngine::systemSleep);
    }

    /**
     * 构造引擎，退避等待可注入。
     *
     * <p>包私有：仅供同包测试替换退避等待，避免测试真睡；生产路径一律走 {@link #WorkflowEngine(RunStore, WorkflowRegistry,
     * ToolRegistry, Clock, int)}。
     *
     * @param store 运行实例存储
     * @param registry 工作流定义注册表
     * @param tools 工具注册表
     * @param clock 时钟
     * @param snapshotLimit 单个运行保留的快照上限
     * @param sleeper 退避等待实现，为 {@code null} 时回退系统睡眠
     */
    WorkflowEngine(
            RunStore store,
            WorkflowRegistry registry,
            ToolRegistry tools,
            Clock clock,
            int snapshotLimit,
            Sleeper sleeper) {
        this.store = Objects.requireNonNull(store, "store");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (snapshotLimit < 1) {
            throw new IllegalArgumentException("snapshotLimit 必须 >= 1，当前值：" + snapshotLimit);
        }
        this.snapshotLimit = snapshotLimit;
        this.sleeper = sleeper == null ? WorkflowEngine::systemSleep : sleeper;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 退避等待的注入点。
     *
     * <p>为什么存在：重试路径上唯一的真实等待就是退避。默认实现走 {@link Thread#sleep}，测试可替换为记录器， 直接断言“第 N 次失败后等了多久”，而不是靠墙钟时间猜。
     */
    @FunctionalInterface
    interface Sleeper {

        /**
         * 等待指定时长。
         *
         * @param duration 等待时长，大于 0
         * @throws InterruptedException 等待被中断
         */
        void sleep(Duration duration) throws InterruptedException;
    }

    private static void systemSleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    /** 返回运行实例存储。 */
    public RunStore getRunStore() {
        return store;
    }

    /** 返回工作流定义注册表。 */
    public WorkflowRegistry getWorkflowRegistry() {
        return registry;
    }

    /** 返回工具注册表，步骤处理器可以从中取工具用。 */
    public ToolRegistry getToolRegistry() {
        return tools;
    }

    /**
     * 启动一次运行并同步跑到底（遇到人工确认会挂起返回）。
     *
     * @param definition 工作流定义
     * @param tenantId 租户 id，不可为空
     * @param initialVars 初始变量，可为 {@code null}
     * @return 运行 id
     */
    public String start(
            WorkflowDefinition definition, String tenantId, Map<String, Object> initialVars) {
        Objects.requireNonNull(definition, "definition");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不可为空");
        }
        log.info(
                "start 启动运行: workflowId={}, version={}, tenantId={}, stepCount={}",
                definition.getWorkflowId(),
                definition.getVersion(),
                tenantId,
                definition.getSteps().size());
        registry.register(definition);
        String runId = RUN_PREFIX + UUID.randomUUID();
        WorkflowInstance instance =
                new WorkflowInstance(
                        runId,
                        tenantId,
                        definition.getWorkflowId(),
                        definition.getVersion(),
                        Instant.now(clock));
        instance.putAllVariables(initialVars);
        for (StepDefinition step : definition.getSteps()) {
            instance.recordStepStatus(step.getStepId(), StepStatus.PENDING);
        }
        save(instance);
        log.info("start 已创建运行: runId={}, tenantId={}", runId, tenantId);
        AgentflowTenant.runWith(tenantId, () -> run(instance, definition));
        return runId;
    }

    /**
     * 从当前步骤继续运行，租户取自 {@link AgentflowTenant}。
     *
     * @param runId 运行 id
     * @throws ApprovalRequiredException 运行正挂着审批单
     * @throws WorkflowNotFoundException 运行不存在
     */
    public void resume(String runId) {
        resume(AgentflowTenant.require(), runId);
    }

    /**
     * 从当前步骤继续运行。
     *
     * @param tenantId 租户 id
     * @param runId 运行 id
     * @throws ApprovalRequiredException 运行正挂着审批单
     * @throws WorkflowNotFoundException 运行不存在
     */
    public void resume(String tenantId, String runId) {
        WorkflowInstance instance = load(tenantId, runId);
        log.info(
                "resume 继续运行: tenantId={}, runId={}, currentStatus={}",
                tenantId,
                runId,
                instance.getStatus());
        PendingApproval pending = instance.getPendingApproval();
        if (pending != null) {
            log.warn(
                    "resume 运行正等待审批，拒绝直接恢复: tenantId={}, runId={}, approvalId={}",
                    tenantId,
                    runId,
                    pending.getApprovalId());
            throw new ApprovalRequiredException(
                    "运行 " + runId + " 正在等待审批（" + pending.getApprovalId() + "），请先给出裁决");
        }
        RunStatus current = instance.getStatus();
        if (current == RunStatus.SUCCEEDED
                || current == RunStatus.FAILED
                || current == RunStatus.COMPENSATED) {
            log.info(
                    "resume 运行已处于终态，跳过恢复: tenantId={}, runId={}, currentStatus={}",
                    tenantId,
                    runId,
                    current);
            return;
        }
        WorkflowDefinition definition = definition(instance);
        instance.setStatus(RunStatus.RUNNING);
        save(instance);
        AgentflowTenant.runWith(tenantId, () -> run(instance, definition));
    }

    /**
     * 对挂起的审批单给出裁决，租户取自 {@link AgentflowTenant}。
     *
     * @param approvalId 审批单 id
     * @param decision 裁决
     * @param comment 审批意见，写入变量 {@code <stepId>.approvalComment}
     */
    public void approve(String approvalId, ApprovalDecision decision, String comment) {
        approve(AgentflowTenant.require(), approvalId, decision, comment);
    }

    /**
     * 对挂起的审批单给出裁决。
     *
     * <p>批准：当前步骤记为成功，索引前进，继续往下跑；拒绝：当前步骤记为失败，触发逆序补偿。
     *
     * @param tenantId 租户 id
     * @param approvalId 审批单 id
     * @param decision 裁决
     * @param comment 审批意见，写入变量 {@code <stepId>.approvalComment}
     */
    public void approve(
            String tenantId, String approvalId, ApprovalDecision decision, String comment) {
        Objects.requireNonNull(decision, "decision");
        if (approvalId == null || approvalId.isBlank()) {
            log.error("approve 审批单 id 为空: tenantId={}", tenantId);
            throw new IllegalArgumentException("approvalId 不可为空");
        }
        log.info(
                "approve 审批裁决: tenantId={}, approvalId={}, decision={}",
                tenantId,
                approvalId,
                decision);
        PendingApproval pending =
                store.findPendingApprovals(tenantId).stream()
                        .filter(item -> item.getApprovalId().equals(approvalId))
                        .findFirst()
                        .orElseThrow(
                                () -> {
                                    log.error(
                                            "approve 未找到审批单: tenantId={}, approvalId={}",
                                            tenantId,
                                            approvalId);
                                    return new WorkflowNotFoundException("未找到审批单：" + approvalId);
                                });
        WorkflowInstance instance = load(tenantId, pending.getRunId());
        WorkflowDefinition definition = definition(instance);
        instance.putVariable(pending.getStepId() + COMMENT_SUFFIX, comment == null ? "" : comment);
        instance.setPendingApproval(null);
        if (decision == ApprovalDecision.APPROVE) {
            instance.recordStepStatus(pending.getStepId(), StepStatus.SUCCEEDED);
            instance.setCurrentStepIndex(instance.getCurrentStepIndex() + 1);
            snapshot(instance, StepStatus.SUCCEEDED);
            instance.setStatus(RunStatus.RUNNING);
            save(instance);
            AgentflowTenant.runWith(tenantId, () -> run(instance, definition));
            return;
        }
        instance.recordStepStatus(pending.getStepId(), StepStatus.FAILED);
        snapshot(instance, StepStatus.FAILED);
        fail(instance, definition);
    }

    /**
     * 回退到指定快照版本，租户取自 {@link AgentflowTenant}。
     *
     * @param runId 运行 id
     * @param snapshotVersion 快照版本号
     */
    public void rollbackTo(String runId, int snapshotVersion) {
        rollbackTo(AgentflowTenant.require(), runId, snapshotVersion);
    }

    /**
     * 回退到指定快照版本：还原变量、当前步骤下标与步骤状态，状态置为 {@link RunStatus#ROLLED_BACK}， 并丢弃该版本之后的快照。
     *
     * @param tenantId 租户 id
     * @param runId 运行 id
     * @param snapshotVersion 快照版本号
     */
    public void rollbackTo(String tenantId, String runId, int snapshotVersion) {
        WorkflowInstance instance = load(tenantId, runId);
        log.info(
                "rollbackTo 回退: tenantId={}, runId={}, snapshotVersion={}",
                tenantId,
                runId,
                snapshotVersion);
        RunSnapshot target =
                instance.snapshot(snapshotVersion)
                        .orElseThrow(
                                () -> {
                                    log.error(
                                            "rollbackTo 快照版本不存在: tenantId={}, runId={},"
                                                    + " snapshotVersion={}",
                                            tenantId,
                                            runId,
                                            snapshotVersion);
                                    return new IllegalArgumentException(
                                            "快照版本不存在：" + snapshotVersion + "，runId=" + runId);
                                });
        WorkflowDefinition definition = definition(instance);
        List<StepDefinition> steps = definition.getSteps();
        Map<String, StepStatus> restored = new LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            String stepId = steps.get(i).getStepId();
            if (i < target.getStepIndex()) {
                restored.put(stepId, StepStatus.SUCCEEDED);
            } else if (i == target.getStepIndex()) {
                // 目标步骤回退后需要重新执行：重置为 RUNNING，而非沿用快照里的终态
                // （可能是 SUCCEEDED），否则会出现「运行已回滚、步骤却显示成功」的语义矛盾。
                restored.put(stepId, StepStatus.RUNNING);
            } else {
                restored.put(stepId, StepStatus.PENDING);
            }
        }
        instance.replaceStepStatuses(restored);
        instance.replaceVariables(target.getVariables());
        instance.setCurrentStepIndex(target.getStepIndex());
        instance.setPendingApproval(null);
        instance.dropSnapshotsAfter(snapshotVersion);
        instance.setStatus(RunStatus.ROLLED_BACK);
        save(instance);
        log.info(
                "rollbackTo 回退完成: tenantId={}, runId={}, snapshotVersion={}, stepIndex={}",
                tenantId,
                runId,
                snapshotVersion,
                target.getStepIndex());
    }

    /**
     * 释放引擎内部的步骤执行器。
     *
     * <p><b>为什么实现 {@link AutoCloseable}：</b>执行器是 {@link
     * Executors#newVirtualThreadPerTaskExecutor()}， 引擎之外没有任何引用能拿到它；虚拟线程虽轻，挂起等待中的线程仍持有堆外与调度资源，
     * 长期不释放即是泄漏。 实现 {@link AutoCloseable} 让 Spring 容器在关闭时自动调用，手动创建的引擎则应在不再使用时显式关闭。
     *
     * <p>关闭只回收执行器，不清存储：已结束的运行仍可查询，回退与审计不受影响。 关闭后新的“带超时步骤”会因
     * 提交被拒而失败（走重试与补偿的正常失败路径），“不限时步骤”不经过执行器，仍可执行。
     */
    @Override
    public void close() {
        log.info("close 关闭工作流引擎，释放步骤执行器");
        executor.shutdownNow();
    }

    /**
     * 查询运行实例。
     *
     * @param tenantId 租户 id
     * @param runId 运行 id
     * @return 运行实例，不存在时返回 {@link Optional#empty()}
     */
    public Optional<WorkflowInstance> find(String tenantId, String runId) {
        log.debug("find 查询运行: tenantId={}, runId={}", tenantId, runId);
        return store.findById(tenantId, runId);
    }

    /**
     * 列出某租户下等待人工确认的审批单。
     *
     * @param tenantId 租户 id
     * @return 审批单列表
     */
    public List<PendingApproval> pendingApprovals(String tenantId) {
        log.debug("pendingApprovals 列出待审批: tenantId={}", tenantId);
        return store.findPendingApprovals(tenantId);
    }

    /**
     * 按状态列出某租户下的运行实例。
     *
     * @param tenantId 租户 id
     * @param status 运行状态
     * @return 运行实例列表
     */
    public List<WorkflowInstance> findByStatus(String tenantId, RunStatus status) {
        log.debug("findByStatus 按状态查询: tenantId={}, status={}", tenantId, status);
        return store.findByStatus(tenantId, status);
    }

    private void run(WorkflowInstance instance, WorkflowDefinition definition) {
        instance.setStatus(RunStatus.RUNNING);
        save(instance);
        log.debug(
                "run 执行运行: runId={}, workflowId={}, stepCount={}",
                instance.getRunId(),
                instance.getWorkflowId(),
                definition.getSteps().size());
        StepStatus lastStatus = StepStatus.PENDING;
        while (instance.getCurrentStepIndex() < definition.getSteps().size()) {
            StepDefinition step = definition.getSteps().get(instance.getCurrentStepIndex());
            if (step.isRequiresApproval()) {
                suspend(instance, step, step.getApprovalPrompt());
                return;
            }
            instance.recordStepStatus(step.getStepId(), StepStatus.RUNNING);
            snapshot(instance, StepStatus.RUNNING);
            save(instance);
            StepResult result;
            try {
                result = executeStep(instance, step);
            } catch (StepTimeoutException e) {
                instance.recordStepStatus(step.getStepId(), StepStatus.TIMED_OUT);
                snapshot(instance, StepStatus.TIMED_OUT);
                lastStatus = StepStatus.TIMED_OUT;
                save(instance);
                log.error(
                        "run 步骤超时: runId={}, stepId={}", instance.getRunId(), step.getStepId(), e);
                fail(instance, definition);
                return;
            } catch (RuntimeException e) {
                instance.recordStepStatus(step.getStepId(), StepStatus.FAILED);
                snapshot(instance, StepStatus.FAILED);
                lastStatus = StepStatus.FAILED;
                save(instance);
                log.error(
                        "run 步骤执行失败: runId={}, stepId={}",
                        instance.getRunId(),
                        step.getStepId(),
                        e);
                fail(instance, definition);
                return;
            }
            instance.putAllVariables(result.getOutput());
            if (result.getStatus() == StepStatus.WAITING_APPROVAL) {
                suspend(instance, step, result.getMessage());
                return;
            }
            lastStatus = result.getStatus();
            instance.recordStepStatus(step.getStepId(), result.getStatus());
            instance.setCurrentStepIndex(instance.getCurrentStepIndex() + 1);
            snapshot(instance, result.getStatus());
            save(instance);
        }
        instance.setStatus(RunStatus.SUCCEEDED);
        snapshot(instance, lastStatus);
        save(instance);
        log.info(
                "run 运行成功: runId={}, workflowId={}", instance.getRunId(), instance.getWorkflowId());
    }

    private StepResult executeStep(WorkflowInstance instance, StepDefinition step) {
        RetryPolicy retry = step.getRetry();
        int attempt = 1;
        while (true) {
            StepResult result;
            try {
                result = invokeWithTimeout(step, newContext(instance, step, attempt));
            } catch (StepTimeoutException e) {
                instance.recordStepStatus(step.getStepId(), StepStatus.TIMED_OUT);
                if (retry.allows(attempt)) {
                    log.debug(
                            "executeStep 步骤超时，退避后重试: runId={}, stepId={}, attempt={}",
                            instance.getRunId(),
                            step.getStepId(),
                            attempt);
                    backoff(retry, attempt);
                    attempt++;
                    continue;
                }
                log.error(
                        "executeStep 步骤超时且重试耗尽: runId={}, stepId={}, attempt={}",
                        instance.getRunId(),
                        step.getStepId(),
                        attempt,
                        e);
                throw e;
            } catch (RuntimeException e) {
                if (retry.allows(attempt)) {
                    log.debug(
                            "executeStep 步骤失败，退避后重试: runId={}, stepId={}, attempt={}",
                            instance.getRunId(),
                            step.getStepId(),
                            attempt);
                    backoff(retry, attempt);
                    attempt++;
                    continue;
                }
                log.error(
                        "executeStep 步骤失败且重试耗尽: runId={}, stepId={}, attempt={}",
                        instance.getRunId(),
                        step.getStepId(),
                        attempt,
                        e);
                throw new RetryExhaustedException(step.getStepId(), attempt, e.getMessage(), e);
            }
            if (result == null) {
                log.warn(
                        "executeStep 步骤处理器返回 null，按失败处理: runId={}, stepId={}",
                        instance.getRunId(),
                        step.getStepId());
                result = StepResult.failure("步骤处理器返回 null：" + step.getStepId());
            }
            if (result.getStatus() == StepStatus.WAITING_APPROVAL
                    || result.getStatus() == StepStatus.SUCCEEDED
                    || result.getStatus() == StepStatus.SKIPPED) {
                return result;
            }
            if (retry.allows(attempt)) {
                backoff(retry, attempt);
                attempt++;
                continue;
            }
            throw new RetryExhaustedException(step.getStepId(), attempt, result.getMessage());
        }
    }

    private StepResult invokeWithTimeout(StepDefinition step, StepContext ctx) {
        TimeoutPolicy policy = step.getTimeout();
        if (policy.isUnlimited()) {
            return step.getHandler().handle(ctx);
        }
        Future<StepResult> future = executor.submit(() -> step.getHandler().handle(ctx));
        try {
            return future.get(policy.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error(
                    "invokeWithTimeout 步骤超时: stepId={}, timeout={}",
                    step.getStepId(),
                    policy.getTimeout());
            throw new StepTimeoutException(step.getStepId(), policy.getTimeout());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                log.error("invokeWithTimeout 步骤执行异常: stepId={}", step.getStepId(), runtime);
                throw runtime;
            }
            if (cause instanceof Error error) {
                log.error("invokeWithTimeout 步骤执行错误: stepId={}", step.getStepId(), error);
                throw error;
            }
            log.error("invokeWithTimeout 步骤执行异常: stepId={}", step.getStepId(), cause);
            throw new IllegalStateException("步骤执行异常：" + step.getStepId(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("invokeWithTimeout 步骤执行被中断: stepId={}", step.getStepId(), e);
            throw new IllegalStateException("步骤执行被中断：" + step.getStepId(), e);
        }
    }

    private void suspend(WorkflowInstance instance, StepDefinition step, String prompt) {
        instance.recordStepStatus(step.getStepId(), StepStatus.WAITING_APPROVAL);
        PendingApproval approval =
                PendingApproval.of(
                        APPROVAL_PREFIX + UUID.randomUUID(),
                        instance.getRunId(),
                        step.getStepId(),
                        prompt,
                        Instant.now(clock));
        instance.setPendingApproval(approval);
        instance.setStatus(RunStatus.WAITING_APPROVAL);
        snapshot(instance, StepStatus.WAITING_APPROVAL);
        save(instance);
        log.info(
                "suspend 挂起等待审批: runId={}, stepId={}, approvalId={}",
                instance.getRunId(),
                step.getStepId(),
                approval.getApprovalId());
    }

    private void fail(WorkflowInstance instance, WorkflowDefinition definition) {
        instance.setStatus(RunStatus.FAILED);
        save(instance);
        log.info("fail 运行失败，开始补偿: runId={}", instance.getRunId());
        compensate(instance, definition);
    }

    private void compensate(WorkflowInstance instance, WorkflowDefinition definition) {
        instance.setStatus(RunStatus.COMPENSATING);
        save(instance);
        List<StepDefinition> steps = definition.getSteps();
        log.info("compensate 开始补偿: runId={}, stepCount={}", instance.getRunId(), steps.size());
        for (int i = steps.size() - 1; i >= 0; i--) {
            StepDefinition step = steps.get(i);
            if (instance.stepStatus(step.getStepId()) != StepStatus.SUCCEEDED) {
                continue;
            }
            Compensator compensator = step.getCompensator();
            if (compensator == null) {
                continue;
            }
            StepContext ctx = newContext(instance, step, 1);
            CompensationResult result;
            try {
                result = compensator.compensate(ctx);
            } catch (RuntimeException e) {
                instance.setStatus(RunStatus.FAILED);
                save(instance);
                log.error(
                        "compensate 补偿失败: runId={}, stepId={}",
                        instance.getRunId(),
                        step.getStepId(),
                        e);
                throw new CompensationFailedException(step.getStepId(), e.getMessage(), e);
            }
            if (result == null || !result.isOk()) {
                String reason = result == null ? "补偿器返回 null" : result.getMessage();
                instance.setStatus(RunStatus.FAILED);
                save(instance);
                log.error(
                        "compensate 补偿未成功: runId={}, stepId={}, reason={}",
                        instance.getRunId(),
                        step.getStepId(),
                        reason);
                throw new CompensationFailedException(step.getStepId(), reason);
            }
            instance.recordStepStatus(step.getStepId(), StepStatus.COMPENSATED);
            save(instance);
        }
        instance.setStatus(RunStatus.COMPENSATED);
        save(instance);
        log.info("compensate 补偿完成: runId={}, status=COMPENSATED", instance.getRunId());
    }

    private void snapshot(WorkflowInstance instance, StepStatus currentStepStatus) {
        int version = instance.latestSnapshotVersion() + 1;
        instance.addSnapshot(
                RunSnapshot.of(
                        version,
                        instance.getRunId(),
                        instance.getCurrentStepIndex(),
                        currentStepStatus == null ? StepStatus.PENDING : currentStepStatus,
                        instance.getVariables(),
                        Instant.now(clock)));
        instance.trimSnapshots(snapshotLimit);
    }

    private StepContext newContext(WorkflowInstance instance, StepDefinition step, int attempt) {
        return new StepContext(
                instance.getRunId(),
                instance.getTenantId(),
                step.getStepId(),
                attempt,
                instance.getVariables(),
                Instant.now(clock));
    }

    private void save(WorkflowInstance instance) {
        instance.touch(Instant.now(clock));
        store.save(instance);
    }

    private WorkflowInstance load(String tenantId, String runId) {
        return store.findById(tenantId, runId)
                .orElseThrow(
                        () -> {
                            log.error("load 未找到运行实例: tenantId={}, runId={}", tenantId, runId);
                            return new WorkflowNotFoundException(
                                    "未找到运行实例：tenant=" + tenantId + "，runId=" + runId);
                        });
    }

    private WorkflowDefinition definition(WorkflowInstance instance) {
        return registry.find(instance.getWorkflowId())
                .orElseThrow(
                        () -> {
                            log.error(
                                    "definition 工作流定义未注册: runId={}, workflowId={}",
                                    instance.getRunId(),
                                    instance.getWorkflowId());
                            return new IllegalStateException(
                                    "工作流定义未注册：" + instance.getWorkflowId());
                        });
    }

    private void backoff(RetryPolicy retry, int attempt) {
        Duration pause = retry.backoffFor(attempt);
        if (pause == null || pause.isZero() || pause.isNegative()) {
            return;
        }
        try {
            sleeper.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("backoff 重试退避被中断: attempt={}", attempt, e);
            throw new IllegalStateException("重试退避等待被中断", e);
        }
    }
}
