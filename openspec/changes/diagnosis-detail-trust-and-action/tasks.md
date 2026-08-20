## 1. 对照审计（先做，避免空改）

- [x] 1.1 打开 `BusinessSummaryCard.vue`，标出：成色折叠位置、`showImpact` 分支、`reviewGuidance` 与按钮关系（改前截图）
- [x] 1.2 读 `DiagnosisExperienceProjection.BusinessSummary` 字段，确认本变更不需要新 API 的前提仍成立
- [x] 1.3 跑 `TroubleshootingChannelSummaryRendererTest`，确认深链断言现状；记录是否仅需配置/回归

## 2. 首屏成色（trust）

- [x] 2.1 在 `BusinessSummaryCard.vue` 结论英雄区增加成色条：映射 `evidenceBasis` + `fixtureMode` + `rehearsal`
- [x] 2.2 保证未展开「查看判断依据」时成色仍可见（二线/三线都要）
- [x] 2.3 更新/新增前端测：REPORTED、RECORDED_REPLAY、rehearsal、fixture+LOCATED 四种首屏可见性

## 3. 二线决策文案（decision）

- [x] 3.1 改 `showImpact===false` 分支：未知声明 + 保守动作（常量放 `formalProjection.ts` 或同级 helper）
- [x] 3.2 锁 EXCLUDED / HYPOTHESIS / INSUFFICIENT 首屏禁用词与必现词（单测或 snapshot）
- [x] 3.3 若前端无法表达，再改 `DiagnosisExperienceProjectionService` 的 nextStep/impact 装配并补 Java 测（本变更前端已够，未改 Java 投影）

## 4. 下一步可执行（actionability）

- [x] 4.1 实现 nextStep×status×权限 → 主按钮高亮或阻断说明的映射；无权限不显示假按钮
- [x] 4.2 回归通道深链：`TroubleshootingChannelSummaryRenderer` + Test；修相对 URL/`workbench-base-url` 缺口（如有）
- [ ] 4.3 手工验证：`/troubleshooting?diagnosisId=<id>` 打开同一单，切换 perspective 不改事实

## 5. 口测完成门（acceptance）

- [x] 5.1 新增 `docs/intelligent-troubleshooting/diagnosis-detail-misread-gate.md` 模板
- [ ] 5.2 二线 10 秒：「要不要升级」——记录 DiagnosisId / 结果 / 误读点
- [ ] 5.3 三线 10 秒：「根因？已知？下一步？」——同上
- [ ] 5.4 失败则只减首屏信息后重测；禁止新卡片族、禁止判定链默认展开
- [ ] 5.5 两次通过后勾选本门完成

## 6. 合并前检查

- [x] 6.1 相关前端单测 + `DiagnosisExperienceProjectionServiceTest` 通过（前端相关 vitest 已过；投影未改故未重跑 Java）
- [ ] 6.2 演练单 + 至少一张正式单目视首屏（成色、影响/保守动作、主按钮）
- [x] 6.3 `openspec validate diagnosis-detail-trust-and-action` 通过
