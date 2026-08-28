# ITDB SQL 审批 Skills 能力蓝图

> 验证日期：2026-08-24<br>
> 目标：将“查待办—看完整 SQL—评估风险—判断可执行—审批通过—核验流转”拆成可组合、可审计的 Skills。

## 1. 结论

ITDB 现有 API 已能支撑一条完整的 SQL 审批链路，不需要直连 Archery 后台数据库：

1. 使用待审清单 API 查当前账号待办。
2. 使用工单详情 API 取完整 SQL、目标库、备份、执行窗口和平台审核结果。
3. 将平台 SQL Check 与本地确定性规则、业务语义评估组合，输出可追溯的风险判定。
4. 仅对命中“可直接通过”策略的单据，在逐单确认后调用审批 API。
5. 通过工单日志和刷新待办核验审批结果。

建议实现为 4 个原子 Skill，由现有 `sf-review-itdb-sql-workflow` 担任编排器。

## 2. 已验证的 ITDB API

OpenAPI 定义为 `Archery API 1.0.0`，支持 `sessionid` Cookie 和 Bearer JWT。

| 能力 | 方法与路径 | 核心输入 | 核心输出/用途 |
|---|---|---|---|
| 登录 | `POST /api/auth/token/` | `username`, `password` | access/refresh token |
| 刷新 token | `POST /api/auth/token/refresh/` | refresh token | access token |
| 待审清单 | `POST /api/v1/workflow/auditlist/` | `engineer` | 当前待审工单、当前审批组、审核状态 |
| SQL 工单详情 | `GET /api/v1/workflow/?workflow_id={id}` | 工单 ID | 完整 SQL、自动审核 JSON、实例 ID、库、备份、窗口、状态 |
| 实例信息 | `GET /api/v1/instance/?id={instance_id}` | 实例 ID | 实例名、类型、主机等信息 |
| SQL 检查 | `POST /api/v1/workflow/sqlcheck/` | `instance_id`, `db_name`, `full_sql` | 警告、错误、是否可执行、影响行数、语句类型 |
| 工单日志 | `POST /api/v1/workflow/log/` | `workflow_id`, `workflow_type` | 操作类型、操作人、操作时间、操作说明 |
| 审批 | `POST /api/v1/workflow/audit/` | 工单、工单类型、备注、`pass/cancel` | 推进或终止审批流 |
| 执行 SQL | `POST /api/v1/workflow/execute/` | 工单、模式 | 执行工单；**不属于审批 Skill** |

当前账号的待审 API 已完成一次只读验证，返回 HTTP 200；验证时待审数为 0。该数字只是当时快照，每次执行必须重新查询。

## 3. Skill 拆分

### Skill A：`SXF-itdb-sql-approval-reader`

职责：只读获取“真正需要当前用户处理”的 SQL 工单及完整证据。

必做检查：

- 从 `auditlist` 而不是从全量 SQL 工单表识别待办。
- 仅处理 `workflow_type = 2` 的 SQL 上线申请。
- 绑定当前登录用户、当前审批组与待审状态。
- 获取完整 `sql_content`，不只看页面截断文本。
- 解析 `review_content`，保留 `stage`/`errlevel`/`errormessage`/`affected_rows`。
- 获取目标实例、数据库、是否备份、执行窗口、需求链接和历史日志。

建议输出：

```json
{
  "ticket_id": 0,
  "ticket_type": 2,
  "requester": "<masked>",
  "current_audit": "<group>",
  "target": {"instance_id": 0, "instance_name": "<name>", "database": "<db>"},
  "sql": {"raw": "<kept in process only>", "sha256": "<hash>", "statement_count": 0},
  "platform_review": {"errors": 0, "warnings": 0, "affected_rows": 0},
  "backup": true,
  "execution_window": {"start": null, "end": null},
  "evidence": []
}
```

安全边界：该 Skill 不调用审批、执行、驳回类接口。

### Skill B：`SXF-itdb-sql-risk-assessor`

职责：对完整 SQL 进行“确定性规则优先、语义判断补充”的分层审核。

审核层次：

1. **语法与平台层**：调用 `sqlcheck`，读取 `is_execute`/`is_critical`/`error_count`/`warning_count`/`affected_rows`。
2. **结构层**：使用 AST 判断 SQL 类型、表、谓词、子查询、跨库访问、函数包装索引列、DDL 等。不允许仅靠关键字正则表达式做最终判定。
3. **性能与锁层**：识别全表写、大范围扫描、热表 DDL、长事务、大批量回填、非索引过滤。
4. **数据安全层**：识别明文密钥/Token/口令、个人信息、越权对象和不可逆更改。
5. **业务语义层**：SQL 对象与需求目的是否一致，状态迁移是否合法，是否缺少前置/后置动作。LLM 可在这一层提供语义建议，但不得覆盖确定性拒绝规则。
6. **可回滚与窗口层**：备份是否存在且可用，是否有逆向 SQL，是否处于适当的低峰窗口。

重要规则：ITDB 页面显示“审核通过”不是自动放行条件。已抽样的工单中，存在平台审核通过但包含 warning、且 DDL 影响行数较大的情况。

输出必须包含：

- `risk_level`: `LOW` / `MEDIUM` / `HIGH` / `BLOCKED`
- `signals`: 命中规则、对象、证据来源、严重程度
- `unknowns`: 无法验证的业务或运行信息
- `rollback_assessment`: 备份、逆向 SQL、恢复耗时
- `recommendation`: `AUTO_APPROVABLE` / `MANUAL_REVIEW` / `REJECT`
- `evidence_grade`: `PLATFORM_CONFIRMED` / `RULE_INFERENCE` / `UNKNOWN`

### Skill C：`SXF-itdb-sql-execution-decision`

职责：把风险信号转化为可执行决策，且实现“任意拒绝项可 veto”。

#### 不可自动通过（任一命中）

- 工单不在当前用户实时待审清单，或审批节点/账号不匹配。
- SQL/ 目标实例/数据库不完整，或快照之后已发生变化。
- `DROP`/`TRUNCATE`/破坏性 DDL，或生产热表 `ALTER TABLE`。
- `UPDATE`/`DELETE` 无有效 `WHERE`，存在恒真条件，或影响范围不可证明。
- 平台检查存在 error/critical，或确定性规则判定为高风险。
- 生产写操作无备份/回滚保障。
- 包含明文凭据、越权写入、跨库写入或无法解释的业务状态跳转。

#### 默认需要人工复核

- 任何 DDL/DCL，包括创建/修改索引。
- 存在 warning、影响行数未知，或超过可配置阈值。
- 多语句工单混合表结构与数据更改。
- 执行窗口为空/无限制，且对象属于生产核心库。
- 需求链接、业务影响或后置验证不充分。

#### 可标记为 `AUTO_APPROVABLE`

必须同时满足：

- 当前实时待办、审批人/审批节点精确匹配。
- 目标实例和库明确，SQL hash 在决策后未变。
- 仅命中可配置的 DML 白名单；默认不包含 DDL/DCL/批量 DELETE。
- 谓词命中主键/唯一键，预估影响行数小于策略阈值（MVP 建议 `<= 10`）。
- 平台检查 0 error、0 critical、0 warning。
- 备份或可证明的回滚方案存在。
- 不包含敏感字面量，业务目的与 SQL 对象一致。
- 不存在任何 `UNKNOWN` 级别的关键前置条件。

阈值应按“实例/数据库/表/语句类型”配置，不应写死在 prompt 中。

### Skill D：`SXF-itdb-sql-approval-submit`

职责：对 `AUTO_APPROVABLE` 工单进行最小打扰的审批提交和结果核验。

提交前必须按顺序完成：

1. 重新调用 `auditlist`，确认工单仍是当前账号待办。
2. 重新取工单详情，比对 SQL hash、实例、库、备份和窗口。
3. 重跑 SQL Check；结果变化立即停止。
4. 只展示一行动作确认：<br>
   `工单 <ID>：低风险，预计影响 <范围/行数>，建议通过；主要剩余风险是 <风险或无明显风险>。现在提交“审核通过”吗？`
5. 只接受针对该工单的明确确认，再调用 `/api/v1/workflow/audit/`，`audit_type = pass`。
6. 读取工单日志并刷新待办，核对“当前节点已推进”。

禁止事项：

- “以后低风险都通过”属于减少中间打扰，不代替每张工单提交前的一次确认。
- 不批量提交，不使用“计划任务无人值守全通过”。
- 不调用 `/execute/`。审批通过只代表审批流转，不代表 SQL 已执行。
- 不仅依赖客户端传入的 `engineer`；服务端必须用 token subject 校验操作人与当前审批节点。
- API 未公开幂等键时，超时后不自动重试写操作；先查日志和待办确认实际状态。

## 4. 编排状态机

```text
DISCOVER_PENDING
  -> LOAD_EVIDENCE
  -> RUN_PLATFORM_CHECK
  -> ASSESS_RISK
  -> DECIDE
       -> REJECT ------------> NOTIFY_AND_WAIT
       -> MANUAL_REVIEW -----> EXPLAIN_AND_WAIT
       -> AUTO_APPROVABLE ---> REVALIDATE
                                -> CONFIRM_ONE_TICKET
                                -> SUBMIT_APPROVAL
                                -> VERIFY_TRANSITION
                                -> DISCOVER_PENDING
```

任何证据缺失、超时、页面/API 结果不一致、SQL hash 改变都应 fail closed，回到 `MANUAL_REVIEW`。

## 5. 安全与凭据治理

提供的企业微信文档中存在明文平台凭据和后台数据库高权限凭据。不应将该文档中的凭据复制到 Skill、仓库、prompt、日志或内存中。

立即处理建议：

1. 轮换已暴露的 ITDB 账号密码与数据库凭据。
2. 删除文档历史版本中的明文凭据，必要时联系文档管理员清理历史。
3. 创建“仅查待审/详情”和“可审批”两类最小权限服务身份；禁止使用 root 直连库获取待办。
4. 凭据存入企业 Secret Manager/本机钥匙串，运行时注入，输出仅显示脱敏验证。
5. 服务端记录谁、何时、对哪张单、根据哪个 SQL hash、命中什么规则、由谁确认。

## 6. MVP 建议

### Phase 1：只读审核助手

- 实现 Skill A + B + C，只输出建议，不持有审批权限。
- 用近 1—3 个月历史工单回放，统计高风险召回率、低风险误放行率和人工复核率。
- 以“低风险误放行率 = 0”作为进入写操作阶段的前置条件。

### Phase 2：单工单确认后审批

- 引入 Skill D，仅对 `AUTO_APPROVABLE` 显示一行确认。
- 服务端强制身份/节点验证、SQL hash 校验、单据锁和审计日志。
- 先在非生产库或一个明确的低风险表白名单试点。

### Phase 3：策略扩围

- 根据回放和真实审批反馈调整阈值。
- 增加表热度、索引、行数、业务窗口、变更冻结期等上下文。
- 仍保留逐单动作确认，不升级为无人值守批量审批。

## 7. MVP 验收用例

| 场景 | 预期结果 |
|---|---|
| 单行、唯一键定位的 UPDATE，审计无告警、有备份 | `AUTO_APPROVABLE`，一行确认后提交 |
| UPDATE 无 WHERE/恒真 WHERE | `REJECT`，不显示审批提交入口 |
| 生产表 ALTER TABLE，即使平台审核通过 | `MANUAL_REVIEW` 或 `REJECT` |
| 平台 0 error 但有 warning | 不可自动通过 |
| SQL 在确认前被修改 | hash 不一致，fail closed |
| 工单已被其他人审批 | 不在待审清单，禁止提交 |
| 审批 API 超时 | 不自动重试；先查日志与待办 |
| 审批成功 | 日志有记录且工单离开当前待办，仅报告流转结果 |
| 审批后未调用 execute API | 符合审批/执行职责分离 |

## 8. 建议的统一审核结果

```json
{
  "ticket_id": 0,
  "sql_sha256": "<hash>",
  "risk_level": "LOW",
  "recommendation": "AUTO_APPROVABLE",
  "can_submit_approval": true,
  "can_execute_sql": false,
  "affected_rows": 1,
  "blocking_reasons": [],
  "residual_risks": [],
  "unknowns": [],
  "evidence": [
    {"source": "itdb_sqlcheck", "grade": "PLATFORM_CONFIRMED", "fact": "0 error, 0 warning"},
    {"source": "local_rule", "grade": "RULE_INFERENCE", "fact": "unique-key bounded DML"}
  ],
  "confirmation_required": true,
  "approval_verified": false
}
```

该结构的关键是将 `can_submit_approval` 与 `can_execute_sql` 分开，并且让每一条结论都有证据等级和来源。
