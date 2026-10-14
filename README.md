# agentflow

Agent 工作流与状态机内核：把一次多步任务拆成步骤依次执行，支持人工确认检查点（挂起后批准或拒绝）、局部重试与退避、单步超时降级、失败后逆序补偿，以及基于快照的版本回退。core 不绑定具体大模型，工具调用走 `Tool` 接口；starter 提供 Spring Boot 自动装配。

## 模块

| 模块 | 说明 |
| --- | --- |
| `agentflow-core` | 零 Spring 核心：StepDefinition/WorkflowDefinition、WorkflowInstance 与步骤状态机、WorkflowEngine（重试/超时/审批/补偿/快照回退）、RunStore（含内存实现）、Tool 与 ToolRegistry、多租户上下文。 |
| `agentflow-starter` | Spring Boot 自动装配：注册引擎、存储与工具注册表；按 `X-Tenant-Id` 隔离租户；提供 `/agentflow/runs` 启动、查询、审批与回退接口。 |
| `agentflow-sample` | 示例应用：合同审批流程，演示正常跑完、挂起批准、拒绝补偿、超时重试四条路径。 |

## 用法

```java
WorkflowEngine engine = new WorkflowEngine(new InMemoryRunStore());

WorkflowDefinition contract =
    WorkflowDefinition.builder()
        .workflowId("contract-approval")
        .name("合同审批")
        .addStep(
            StepDefinition.builder("extract-terms",
                    ctx -> StepResult.success(Map.of("amount", ctx.get("amount"))))
                .name("抽取要素")
                .retry(RetryPolicy.fixed(3, Duration.ofMillis(200)))
                .timeout(TimeoutPolicy.of(Duration.ofSeconds(5)))
                .compensator(ctx -> CompensationResult.ok("已清除要素草稿"))
                .build())
        .addStep(
            StepDefinition.builder("risk-check",
                    ctx -> ((Number) ctx.get("amount")).intValue() > 1_000_000
                        ? StepResult.waitApproval("金额超过阈值，请人工确认", Map.of("risk", "HIGH"))
                        : StepResult.success(Map.of("risk", "LOW")))
                .name("风控校验")
                .compensator(ctx -> CompensationResult.ok("已撤销风控记录"))
                .build())
        .build();

String runId = engine.start(contract, "acme", Map.of("amount", 1_500_000));

// 高风险步骤挂起，等人工确认：批准则继续往下跑，拒绝则逆序补偿已成功的步骤
PendingApproval pending = engine.find("acme", runId).orElseThrow().getPendingApproval();
engine.approve("acme", pending.getApprovalId(), ApprovalDecision.APPROVE, "同意签署");

// 按快照回退到某个历史版本
engine.rollbackTo("acme", runId, 1);
```

引入 starter 后，同样的流程可以直接走 HTTP：

```bash
curl -X POST http://localhost:8080/agentflow/runs \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: acme' \
  -d '{"workflowId":"contract-approval","variables":{"amount":1500000}}'

curl -X POST http://localhost:8080/agentflow/runs/<runId>/approve \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: acme' \
  -d '{"decision":"APPROVE","comment":"同意签署"}'
```

配置项写在 `application.yml`：

```yaml
agentflow:
  tenant-header: X-Tenant-Id
  default-tenant-id: default
  default-step-timeout: 30s
  default-max-attempts: 3
  snapshot-limit: 50
```

## 构建

```bash
./mvnw -B verify
```

`verify` 会跑编译、单元测试、集成测试和 JaCoCo 门禁（整体 >= 0.60，core >= 0.75）。

## 架构约束

`agentflow-core` 严禁依赖 `org.springframework.*`（CI 的 `architecture-boundaries` job 强制扫描）。
