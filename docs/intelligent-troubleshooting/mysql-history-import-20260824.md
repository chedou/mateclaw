# MySQL 历史数据导入记录（2026-08-24）

## 结果

已将 `mateclaw_local` 中的用户可见历史数据追加到 `mateclaw_sit`，没有覆盖目标库原有数据。

| 数据 | 导入前 | 导入 | 导入后 |
|---|---:|---:|---:|
| 会话 `mate_conversation` | 6 | 16 | 22 |
| 消息 `mate_message` | 18 | 62 | 80 |
| 诊断 `mate_troubleshooting_diagnosis` | 0 | 77 | 77 |

原有 `tasks_1` 仍保留 8 条消息；历史同名会话被安全映射为 `legacy_local_v221__tasks_1`，共 16 条消息。

## 安全边界

- 源库锁定 Flyway V221，目标库锁定 V223，两边均无失败迁移。
- 仅允许追加会话、消息和诊断三张表。
- Intake、队列、Claim、Run、SOP、证据路由、Agent 和数据源配置均未导入，避免历史记录触发新的调查任务。
- 历史诊断的去重键、Intake 关联和通知状态已中和，不可调度。
- 会话文本、消息内容/JSON 元数据、诊断聚合 JSON 已执行确定性凭据脱敏。计划与导入后的残留扫描均为 0，77 条诊断 JSON 全部有效。
- 未执行 `UPDATE`、`DELETE`、`REPLACE`、`INSERT IGNORE` 或 upsert。

## 备份与幂等验证

- 导入前备份：`backups/mysql-history-import-20260824/mateclaw_sit-pre-history-import-20260824-151211.sql`
- 备份权限：`0600`
- 备份大小：`7,942,852 bytes`
- SHA-256：`836a9866b0f55439f1e47edeccc5de1fa1d9a206e6717abdef5bef3c81a164e6`
- 导入后再次运行计划：`0 insert / 155 exact / 0 conflict`。

## 可重复执行的工具

- 执行入口：`scripts/import-mysql-history.sh`
- 合并器：`scripts/mysql-history-merge.java`
- 安全门禁：`scripts/ci/test-mysql-history-merge-safety.sh`

默认先运行 `plan`；`apply` 必须额外设置 `MATECLAW_HISTORY_ALLOW_APPLY=true`，并会在写入前重新计划、生成备份，写入后再次验证幂等性。数据库密码只能通过 `MATECLAW_HISTORY_DB_PASSWORD` 环境变量传入。

## 导入后的队列清理（2026-08-24）

为避免 Demo/Test 历史占用正式排障队列，已将下列 67 条导入诊断软归档（`deleted=1`）：

- `rehearsal=1` 的演练诊断；
- 诊断聚合中 `fixtureMode=true` 的夹具诊断。

写入前已确认候选记录没有活跃 Claim、调查、Outbox、通知或诊断子记录。清理后队列保留 10 条正式、非夹具诊断：6 条 `CONFIRMED`、1 条 `NEEDS_INVESTIGATION`、3 条 `READY_FOR_HUMAN`。会话、消息、Intake、SOP、证据和接入配置均未修改。

- 清理前备份：`backups/mysql-queue-cleanup-20260824/.mateclaw_sit-before-queue-cleanup-20260824-154948.sql`
- 备份权限：`0600`
- 备份大小：`8,416,238 bytes`
- SHA-256：`153eff927d78e130bc4a9e30a484c9714cde4bc84c34a91bfb271f4028454f07`

注意：前述 `0 insert / 155 exact / 0 conflict` 是软归档前的幂等结果。当前 67 条诊断的 `deleted` 状态已发生有意变化，原历史导入脚本会把它们识别为冲突；除非先恢复这些记录或升级导入器以识别已归档批次，否则不要再次执行原导入。
