# T7 Guance 目标合同准备队列

> 本文件由 `l0/t7_target_preparation.py` 从当前 L0、冻结 D1 清单、录制套件和目标目录确定性生成。
> 它只用于窗口外分工，**不能授权 T7、不能替代运行服务目录或预检**。

## 当前结论

- 冻结 D1 分母：**146**。
- 清洗后的只读候选：**30**；其中已录制 **1**、源码质量阻断 **1**、待 owner 补合同 **28**。
- 准备队列中已写入目标目录、无源质量阻断且仍待运行时验证：**0**；T7 窗口要求运行服务投影至少 **20** 个可执行目标。
- 当前结论始终是 `PREPARATION_ONLY`；只有 `recording-targets` 运行时接口 + T7 预检可以发布可执行结论。

## 缺失字段说明

- `owner_team / owner_level / owner_scenario`：由业务 owner 核对责任团队、等级和故障场景。
- `verified_runtime_service / safe_search_term / log_signature_or_query_key`：核对真实运行服务和安全检索键；不得在本队列填写 DQL 或原始日志。
- `server_query_contract / current_binding_refs`：由服务端配置维护查询模板及当前 `log_search / log_trace_bundle / contrast_sample` 三份 bindingRef。
- `deterministic_anomaly_criteria / deterministic_diagnosis_rule`：给出可复算异常判据与诊断规则，不能使用模型自报置信度替代。
- `historical_occurred_at`：仍在保留期内的精确历史故障时间；批次模式不得回落当前时间。
- `source_quality_resolution`：先解决源材料冲突；`runtime_preflight / owner_acceptance` 只能在完整合同冻结后执行。

## Owner 补齐队列

| Selector | 等级 | 来源服务 | 日志签名提示 | 状态 | 仍缺 |
|---|---|---|---|---|---|
| `csdp:101014` | P0 | 客户IM / 客服侧 | 有 · Cti_DispatchPulsarFailed_001 | `BLOCKED_SOURCE_QUALITY` | source_quality_resolution / owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:101004` | P0 | 客服侧 | 有 · Customer_IMLoginDataFail_001 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:101010` | P0 | 客户IM | 有 · Cti_DispatchPulsarFailed_001 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:101015` | P0 | 客服侧 | 有 · Cti_EnqueueFailed_001 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:201003` | P0 | 渠道侧 | 有 · Partner_IMLoginDataFail_001 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:201011` | P0 | 客户IM | 有 · Conversation_AcceptFailed_001 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:401007` | P0 | CTI侧 | 有 · Conversation_CspLoginFail_001 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:901002` | P0 | 客服侧 | 有 · Customer_GetOpenIdFailed_001 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:IM2002` | P0 | 客户IM | 有 · Middleware_MsgCacheFailed_001 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:IM3002` | P0 | 客户IM | 有 · Middleware_MsgPersistDBFailed_001 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:301002` | P1 | 客服侧 | 有 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:501001` | P1 | CTI侧 | 有 · Third_CTILoginDataFail_001 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:Workorder_CustomerDetailFail_004` | P1 | csdp-wechat | 有 · Workorder_CustomerDetailFail_001 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:Workorder_CustomerListFail_003` | P1 | csdp-wechat | 有 · Workorder_CustomerListFail_001 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:Workorder_EmergencyCreateFail_005` | P1 | csdp-wechat | 有 · Workorder_EmergencyCreateFail_001 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:Workorder_UpgradeServiceFail_006` | P1 | csdp-wechat | 有 · Workorder_UpgradeServiceFail_001 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at |
| `csdp:901004` | P1 | 客服侧 | 无 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at / log_signature_or_query_key |
| `csdp:301035` | P2 | 客服侧 | 无 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at / log_signature_or_query_key |
| `csdp:101011` | — | 客服侧 | 无 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at / owner_level / owner_scenario / log_signature_or_query_key |
| `csdp:101017` | — | 客服侧 | 无 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at / owner_level / log_signature_or_query_key |
| `csdp:101031` | — | 客服侧 | 无 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at / owner_level / owner_scenario / log_signature_or_query_key |
| `csdp:101062` | — | 客服侧 | 无 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at / owner_level / log_signature_or_query_key |
| `csdp:301012` | — | 客服侧 | 无 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at / owner_level / owner_scenario / log_signature_or_query_key |
| `csdp:301030` | — | 客服侧 | 无 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at / owner_level / owner_scenario / log_signature_or_query_key |
| `csdp:301032` | — | 客服侧 | 无 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at / owner_level / owner_scenario / log_signature_or_query_key |
| `csdp:301040` | — | 渠道侧 | 无 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at / owner_level / owner_scenario / log_signature_or_query_key |
| `csdp:301042` | — | 客服侧 | 无 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at / owner_level / owner_scenario / log_signature_or_query_key |
| `csdp:301045` | — | 客服侧 | 无 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at / owner_level / log_signature_or_query_key |
| `csdp:903001` | — | csdp-wechat | 无 | `NEEDS_OWNER_CONTRACT` | owner_team / verified_runtime_service / server_query_contract / safe_search_term / deterministic_anomaly_criteria / deterministic_diagnosis_rule / current_binding_refs / historical_occurred_at / owner_level / owner_scenario / log_signature_or_query_key |
| `csdp:IM1010` | P0 | 客户IM | 有 · Middleware_MsgSendMQFailed_001 | `ALREADY_RECORDED` | — |

## 使用顺序

1. 先处理 `BLOCKED_SOURCE_QUALITY`，不能在冲突路由键上猜一个上下文。
2. Owner 优先复制 `t7-owner-contract-intake.recommended.template.json`：它已按审核过的 15 A + 2 B + 3 C 选好 20 条并展开全部字段；如需调整批次再使用空白模板。
3. 逐项替换所有 `<replace:...>` 占位符，核对真实运行 service、服务端查询合同、安全 search term、判据/规则、bindingRef 和历史故障时间。
4. 执行 `t7_owner_contract_intake.py --validate <受控文件>`；通过仍只表示 `PREPARED_NOT_EXECUTABLE`，不是 T7 授权。
5. 开发者根据已核实引用编写完整、安全、未验证的 candidate，与当前三份 bindingRef 冻结进 `guance-recording-targets.json`；本报告和 owner 输入工具都不生成该文件。
6. 重启运行服务并执行 `T7_SEED_PLAN_FILE=<受控计划> ./scripts/troubleshooting-t7-preflight.sh`。只有运行时返回 20–30 个可执行目标才可约窗口。
7. 窗口里由 owner 完成清单并提交 `ACCEPTED`，随后一次灌入 20–30 份 D19 聚合正例。

本队列不含原始日志、DQL、凭据或 API Key；日志签名只投影是否存在及安全错误标识符。
