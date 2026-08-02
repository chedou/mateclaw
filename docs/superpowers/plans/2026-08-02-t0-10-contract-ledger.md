# T0.10 稳定契约实现台账实施计划

**目标：** 让 v4 §5 的目标合同与当前运行时实现可在同一页区分，并完成 TODO 中的逐项判定。

**约束：** 本轮只修改文档；不新增运行时合同，不迁移 selector，不改变 API、数据库或测试行为。

## 任务 1：建立 RFC 实现状态台账

**文件：** `rfcs/intelligent-troubleshooting-architecture-v4.md`

1. 在 §5 开头定义四种状态。
2. 覆盖 §5.1–§5.11，并补入 `ScenarioProposal` 与 `ReadOnlyEvidenceToolRegistry`。
3. 为每项写出当前代码落点、差异和下一触发条件。
4. 用 `rg` 复核台账中的类名都能在仓库找到，或被明确标为未实现。

## 任务 2：关闭 T0.10 结构账

**文件：** `docs/intelligent-troubleshooting/TODO.md`

1. 勾选“逐条判定”和“实现状态表”。
2. 记录不立即创建 `EvidenceBundle` 和不迁移 `SopEntry` 的理由与触发条件。
3. 保持 T7、T0.8、P4/P5 的现有门禁不变。

## 任务 3：更新交接恢复点

**文件：** `docs/intelligent-troubleshooting/HANDOFF.md`

1. 在总体进度中加入结构账事实。
2. 明确本轮没有推进真实样本或新增能力。

## 任务 4：验证

运行：

```bash
rg -n "IMPLEMENTED|PARTIAL|NOT_IMPLEMENTED|PENDING-EVIDENCE" \
  rfcs/intelligent-troubleshooting-architecture-v4.md
rg -n "T0.10|EvidenceBundle|ReadOnlyEvidenceToolRegistry|ScenarioProposal" \
  docs/intelligent-troubleshooting/TODO.md \
  docs/intelligent-troubleshooting/HANDOFF.md \
  rfcs/intelligent-troubleshooting-architecture-v4.md
git diff --check
```

检查 `git diff`，确认只有上述文档变化且没有把目标合同写成已实现事实。
