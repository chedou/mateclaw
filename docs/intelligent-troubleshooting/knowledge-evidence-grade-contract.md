# Playbook 知识证据等级合同

> 状态：T0.9，2026-08-02 起生效
> 目标：让“已批准”和“判据真的来自数据”成为两条可见、不可混用的轴

## 1. 为什么需要单独分级

`status=approved` 只证明候选经过了治理流程，不能证明判据阈值来自真实观测。
`fixtureMode` 只描述某一次 Diagnosis 的证据是否回放，也不能回答 Playbook 知识是如何形成的。

证据等级必须冻结在不可变 Playbook 版本上，并投影到注册表和开发证据视图；
不得复用 `approved` 或 `fixtureMode` 冒充知识权威。

## 2. 等级

| 等级 | 含义 | 当前例子 |
|---|---|---|
| `RECORDED_AGGREGATE` | 判据来自按 D19 合同脱敏、聚合并可回放的真实历史事实 | `csdp:IM1010` |
| `AUTHORED_FIXTURE` | 判据为固定验证或演示而人工编写，可证明机制，不证明真实分布 | `csdp:903001` |
| `UNVERIFIED` | 历史或外部数据未能证明来源；空值、未知值都保守落到这里 | 兼容历史数据 |

回放目录按服务端受控 lane 分级，浏览器或候选请求不能自行上调等级。
目录只在 selector 与候选内容指纹都精确匹配服务端冻结示例时授予等级；复用已知
selector、但改写根因或判据的候选仍然没有来源权威。未知 selector 没有目录来源等级时
不得晋升。

## 3. 持久化与投影

- V190 在 H2、MySQL、Kingbase 的 Playbook 版本表增加
  `knowledge_evidence_grade`，默认 `UNVERIFIED`。
- SQL 不根据公开的 selector 或 source ID 猜测历史权威，所有旧版本先保持
  `UNVERIFIED`。启动协调器从冻结 `aggregate_json` 重建原候选并复算 canonical SHA-256，
  只有完整内容精确匹配服务端示例时才一次性升级；坏 JSON、冒用 ID 或改写内容继续
  `UNVERIFIED`。协调器按 ID keyset 分页扫描，永久不匹配的早期坏记录不会遮住后续精确候选。
- 精确匹配的录制种子 `IM1010` 升级为 `RECORDED_AGGREGATE`；精确匹配的 `903001`
  与部署拓扑手写套件升级为 `AUTHORED_FIXTURE`。
- 等级随冻结 Playbook 引用进入 `DeveloperEvidenceView`；没有 Playbook 时不伪造等级，
  有历史 Playbook 但值不可识别时显示 `UNVERIFIED`。
- 注册表列表、详情和筛选区使用相同服务端值，不由前端猜测。

`903001` 继续保持 approved，是因为它仍承担“人工批准不等于执行”和固定回放
bootstrap 的机制证明；它不再与真实录制知识平级展示，权威边界由
`AUTHORED_FIXTURE` 明示。

## 4. 覆盖计数

`GET /api/v1/troubleshooting/sops/evidence-coverage` 返回固定 D1 清单分母和分项计数：

- `inventoryErrorCodeSelectors = 146`
- `registryErrorCodeSelectors`
- `recordedAggregateSelectors`
- `authoredFixtureSelectors`
- `unverifiedSelectors`
- `outsideInventorySelectors`

146 个成员冻结在
`troubleshooting/knowledge/csdp-d1-error-code-selectors.json`，计数以成员关系为准，
而不是“碰巧有 146 行”为准。场景 selector 不进入错误码清单；CSDP 中不属于该清单的
错误码另计入 `outsideInventorySelectors`，不能挤占分母或分项。接口刻意不返回百分比；
`1 / 146` 与 `20 / 146` 必须让读者看见真实分母，不能都渲染成脱离样本量的“覆盖率”。

## 5. 与 T7/T0.8 的顺序

T0.9 已在 T0.8 剩余 145 条批量导入前完成。T7 窗口的目标仍是一次录入
20–30 条真实种子；每条新种子必须带服务端可验证的来源等级。没有真实聚合事实的知识
不得标为 `RECORDED_AGGREGATE`，也不得因为已 approved 而被计入真实录制覆盖。
