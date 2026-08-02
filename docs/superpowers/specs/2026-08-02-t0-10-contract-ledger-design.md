# T0.10 稳定契约实现台账设计

## 背景

`rfcs/intelligent-troubleshooting-architecture-v4.md` 第 5 章同时包含已上线合同、兼容命名和
`PENDING-EVIDENCE` 目标形态，但此前统一称为“稳定契约”。代码中不存在若干同名类型，维护者无法只靠
RFC 判断某个合同是否已经实现。

## 决策

本轮采用“先校正文档事实，再由触发条件驱动代码收敛”的方案：

1. 在 RFC §5 开头建立实现状态台账，逐项标记 `IMPLEMENTED`、`PARTIAL`、
   `NOT_IMPLEMENTED` 或 `PENDING-EVIDENCE`，并列出当前运行时名称。
2. 对 `ScenarioProposal`、`ReadOnlyEvidenceToolRegistry` 等跨章节目标也纳入台账，确保 T0.10 列出的
   名称没有漏项。
3. `SopEntry → type + selector` 留到 T10.5 / P4：当前 routing key 已进入版本与持久化边界，不能在结构账
   中顺带迁移。
4. `EvidenceBundle` 暂不新增空壳类型：当前证据归属分散在 `EvidenceSpineResult`、
   `PlaybookEvidenceAssessment`、`Diagnosis.evidence` 等边界，尚无统一 `planId`、bundle identity 和
   fixture 归属。等 InvestigationPlan 与持久化边界确定后一次收敛，避免长期存在“新包装 + 旧裸列表”双轨。
5. `Loop*`、`AdversarialEvalReport`、`DiscoveryPolicy` 继续不实现，服从 D16 的真实样本门禁。

## 方案比较

- **采用：实现台账优先。** 最小改动即可消除文档误导，不改变 API、数据库或判定链。
- **暂缓：只新增 EvidenceBundle 值对象。** 在身份、计划绑定和持久化语义未定时只会增加第二种证据容器。
- **拒绝：一次性重命名并迁移全部合同。** 会把 P4/P5 的未验证形状提前固化，并扩大 selector 持久化风险。

## 验收

- RFC §5 能直接回答每个合同“当前是否存在、真实代码名是什么、何时再收敛”。
- T0.10 列出的全部缺失或异形合同均有明确判定。
- TODO 与 HANDOFF 记录同一事实，不宣称 T7、P4 或 P5 前进。
- 仓库不新增运行时类型、接口、表结构或依赖。
