# IM1010 真实业务回放与可扩展晋升设计

## 目标

解决 T0.8 暴露的主干问题：错误码 Playbook 不能继续依赖“每个错误码人工手写命中、排除、弃权三套回放”。首个落地样板选择 `csdp:IM1010`（客户 IM 消息发送失败），使用仓库已经记录过的 2026-07-31 Guance 真源预览的脱敏聚合事实，跑通“候选 → 服务端回放证明 → 知识审核晋升 → Recorded Replay 取证 → HTTP Diagnosis → 双投影”的完整链路。

本轮所称“真实”有两层明确边界：

- 业务场景、错误码知识和只读排查动作来自 L0 运营知识中的真实 `IM1010` 路由；
- 正例使用已有运行记录中的脱敏聚合事实：失败检索命中 `2`，失败 cohort `2/2`，显式成功 cohort `0/14047`。不保存原始日志、PS ID、DQL 或凭据。

由于该次真源运行没有提交 Workspace owner 的 T7 acceptance，回放诊断仍必须显示 `fixtureMode=true`。本轮只证明真实业务样板能够可重复跑通，不把它写成“实时 Guance 已验收”，也不把消息发送失败过度归因为 Kafka Broker 宕机。

## 方案比较与决策

### 方案 A：每个错误码继续维护完整固定套件

优点是显式、容易审计；缺点是 146 个路由至少需要 438 份手写用例，维护成本随错误码线性增长，也会让拼写和字段漂移成为主要失败来源。不采用。

### 方案 B：降低错误码晋升门槛

例如只要求正例，不要求排除或弃权。这样最省实现，但会破坏 D5/D5′：一个永远命中的判据也能被批准。不采用。

### 方案 C：历史正例 + 判据形状模板生成反例/弃权例

采用该方案。每个新路由只提供一个服务端持有、已脱敏的正例种子和候选 Playbook；纯 Java 模板根据封闭的 `Criterion` 形状生成：

1. 历史正例：保留已记录的 canonical observed facts，并声明预期命中的精确规则；
2. 确定性反例：为候选的每个判据构造一个可求值但为假的 counterexample，保留其余 canonical 字段；
3. 缺证据弃权例：同一批 request ID 全部返回 `MISSING`。

模板生成后仍交给现有 `ManualPlaybookReplayEvaluator` 逐例执行。生成器不是新的裁决权威；它只是减少重复输入，最终通过条件仍是精确 selector、精确 EvidenceRequest 合同、正例命中、反例排除和缺证据弃权全部成立。

## 设计

### 1. RecordedEvidenceSeed 合同

回放目录升级为兼容的 v2 文档：继续接受既有手工 `suites`，新增 `recordedEvidenceSeeds`。每个种子包含：

- 套件 ID、版本和 selector；
- 一个 `candidate` 状态、`verified=false` 的完整 `SopEntry`；
- 一个必需证据 request ID；
- 一个脱敏正例及精确 `expectedRuleId`；
- 有界的 `sourceReference`，用于说明聚合事实来源。

浏览器仍不能上传 fixture、预期答案或生成策略。种子和模板都来自 classpath，最终 suite fingerprint 继续由服务端计算。

### 2. CriterionShapeReplayTemplate

生成器覆盖当前封闭的六种判据：

| 判据 | 生成的反例 |
|---|---|
| `numeric_gte` | 字段取严格小于阈值的有限数 |
| `missing_or_lte` | presence 为真，数值严格大于阈值 |
| `ratio_of_sum_gt` | 构造比例不大于阈值 |
| `multiple_gt` | baseline 取 1，字段值等于 multiplier |
| `contains_and_in` | 构造不包含或不属于 accepted values 的字符串 |
| `boolean_equals` | 布尔值取反 |

如果两个判据要求对同一字段写入冲突值、无法生成有限 counterexample、正例没有完整 request ID，或生成后 evaluator 不能得到精确三态，种子必须被拒绝，不能退化成猜测。

### 3. 启动隔离

既有手工固定套件属于平台基线，格式或自测失败继续 fail-fast。规模化新增的 `recordedEvidenceSeeds` 逐条隔离：一条坏种子被 quarantine 并记录有界 rejection code，其他套件和平台仍可启动。仓库测试必须保证随仓 `IM1010` 种子没有被 quarantine，避免“能启动但样板消失”。

### 4. IM1010 Playbook

`csdp:IM1010` 使用 `csp-rpc-msg` 服务和两类 canonical evidence：

- `log_search`：`message_send_failed` 的命中数；
- `contrast_sample`：失败 cohort 与显式成功 cohort 的固定故障特征对照。

确定性规则要求“失败日志存在”且“故障特征在失败样本中占优”。输出限定为“消息投递链路异常，MQ Producer/Broker/网络需继续只读核查”，置信度为 `MEDIUM`；不直接宣称 Kafka 集群宕机。建议动作只有只读取证和人工联系，不包含自动生产写。

### 5. Demo 与 HTTP 验收

Demo seeder 不再在 Java 中复制一份 Playbook，而是对 server-owned example selector 列表逐条执行现有晋升流程：注册 candidate、运行 replay、开始 review、以明确的 demo actor 批准。首版同时保留 `903001`，新增 `IM1010`。

`scripts/troubleshooting-smoke.sh` 的默认业务样板切换为 `CSDP / csp-rpc-msg / IM1010`。GitHub workflow 等待 `csdp:IM1010` 成为 approved 后，再跑原有八道 HTTP 闸门。成功必须同时证明：

- Playbook 是通过 replay + review 晋升得到的，而不是状态直改；
- Recorded Replay 返回正例聚合事实；
- Diagnosis 不是 `INSUFFICIENT_EVIDENCE`；
- 双投影可读，且 `fixtureMode=true`；
- 整条链路不访问 Guance。

## RFC 决策

发布新的 v0.19 / RFC v4.5 快照，并在 §5.7 增加 D19：错误码 `MANUAL` Playbook 的回放证据默认采用“已脱敏 recorded positive + criterion-shape generated negative/missing”模式；生成不会降低资格门，不会替代 owner/reviewer，也不会把 Recorded Replay 冒充 T7/T8 真源验收。历史 v0.18 保持不修改。

## 非目标

- 本轮不一次性导入 146 个错误码，只提供可复用机制和一个可运行样板；
- 不新增数据库表，不保存原始日志或完整 PS ID；
- 不自动提交 owner acceptance，不关闭 `fixtureMode`；
- 不改变 `EVIDENCE_DERIVED`、`OUTCOME_BACKED` 的晋升规则；
- 不新增 LLM 调用，也不允许模型生成 replay 预期答案。

## 验证

- 单元测试覆盖六种判据反例生成、字段冲突 fail-closed、正例/反例/弃权三态；
- Catalog 测试覆盖 v1 兼容、v2 IM1010 装载、坏种子隔离和 fingerprint 稳定形状；
- Replay/Seeder 测试覆盖 IM1010 candidate 与 recorded catalog 精确对齐、只读动作和正常晋升；
- Shell 合同测试锁定 workflow 等待 `IM1010`，smoke 默认使用 `csp-rpc-msg / IM1010`；
- 在本地实际启动 `dev,troubleshooting-demo` 后运行八闸门 HTTP smoke，保存终端证据；若 Maven 仍被 DNS 阻断，则静态/单测证据照常保留，并明确把真实进程验收留给 DNS 可用的 CI，不能伪称本地已跑通。
