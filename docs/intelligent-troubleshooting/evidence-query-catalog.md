# 配置与接入：取证接入与查询规则说明书

> 状态：方法库侧栏统一交互（2026-08-12）
>
> **系统清单唯一主入口 = 接入系统。** 方法本体在「取证方法」方法库维护（支持通用 / 指定系统 / 指定模块）；模块里只勾选与填参数。部署 YAML 仍可作为已发布基线；API Key 仍环境注入，列表接口不回传查询模板给普通查看者。

## 0. 交互规范（必须遵守）

1. **取证方法库、接入系统模块工作台：统一用右侧侧栏完成新增/修改/试跑，禁止再叠弹窗。**
2. 方法库每一行（含「部署发布」来源）都必须可点 **「修改」** 进入侧栏编辑；保存后写入 Workspace 版本，覆盖同名部署发布方法。
3. 「新增取证方法」同样打开侧栏，不弹窗。
4. 模块配置继续走模块侧栏内的「去填配置」面板，不另开弹窗。

## 0.1 观测云固定方法与可选条件

部署基线（`application.yml`）提供以下 **GENERIC** 通用方法，按观测云已核实能力固化，可被任意模块勾选：

| contractRef | signalKind | 缺外部字段时怎么查 |
|---|---|---|
| `guance-service-log-count` | `log_count` | 仅按报障 `service` + 时间窗计数；有 `error_code` 再收窄 |
| `guance-service-log-search` | `log_search` | 仅按服务取样；可选 `error_code` / `search_term` |
| `guance-service-trace-bundle` | `log_trace_bundle` | 需要 `ps_id`（前步证据）；按服务过滤 |
| `guance-service-error-scan` | `error_log_scan` | 按服务统计 `level:ERROR`；可选 `error_code` |
| `guance-monitor-event-scan` | `monitor_event_scan` | 未传 `monitor_checker` 时统计窗口内全部 warning+ |
| `guance-k8s-workload-health` | `k8s_workload_health` | `deployment`/`namespace` 必须由模块资产固定，不可缺 |
| `guance-service-pod-status` | `k8s_pod_status` | 按报障 `service` 统计 Pod；可选 `deployment`/`namespace`/`node_name` |
| `guance-service-node-status` | `k8s_node_status` | 按服务反查 `node_name`/`host` 与资源水位；可选 `node_name` 等收窄 |
| `guance-service-host-status` | `host_status` | 按服务反查落地主机与 CPU/内存水位；可选 `host` 收窄 |
| `guance-service-synthetic-probe` | `synthetic_probe` | `probe_name` 须由模块资产固定（ASCII 安全字符）；中文拨测任务名写进场景方法的固定 DQL |

CSDP 联调试点另提供 `csdp-session-synthetic-probe`：固定查询「客服数字化平台-首页-可用性监控」。

模板可选片段语法（服务器渲染，fail-closed）：

```text
{ `df_status` IN ['critical', 'error', 'warning']{{?monitor_checker}} AND `df_monitor_checker_name` = '{{monitor_checker}}'{{/monitor_checker}} }
```

- 有值：保留 `{{?name}}...{{/name}}` 区块并替换内部 `{{name}}`
- 无值/空白：整段删除，查询仍可执行
- 必填占位符（无 `?`）缺失仍报错
- `search_term` 等插值仍须满足安全字符集（字母数字与 `._:/-`）；中文关键词写进固定 DQL，不要当外部参数

CSDP 场景方法仍在 `application-csdp-guance-evidence-pilot.yml`。

## 1. 分工

| 页面 | 路由 | 只做什么 |
|---|---|---|
| **接入系统**（主入口） | `/troubleshooting/observability-assets?section=modules` | 登记系统/模块；点进模块侧栏勾选取证方法、看观测云缺口、试跑 |
| **取证方法**（方法库） | `?section=tools` | 侧栏维护方法本体与作用域；部署与页面方法均可修改 |
| **数据连接**（更多配置） | `?section=source` | 平台级检查观测云能否只读读取 |
| **排障规则库** | `/troubleshooting/sops` | 场景何时调用哪些方法（Playbook） |
| **数据源联调** | 工作台 `capability=guance` | 观测云端点/凭据与 owner 真源验收（页面不存 API Key） |

## 2. 菜单位置

`智能排障`：

1. **接入系统**（日常主入口）——登记系统模块，并在模块里选择取证方法
2. **更多配置**——取证方法库、数据连接、排障规则库
3. **复盘与沉淀**——效果评估、案例知识

## 3. 方法库与模块绑定

1. **方法库**：`GET/PUT /api/v1/troubleshooting/evidence/contracts`  
   - 作用域：`GENERIC`（通用）/ `SYSTEM` / `MODULE`  
   - Workspace 版本可覆盖同名部署发布方法；列表不返回 queryTemplate，详情（admin）才返回  
   - UI：列表 → 侧栏编辑（禁止 `el-dialog` 叠层）
2. **模块勾选**：接入系统侧栏里绑定 `signalKind → contractRef`，并受作用域约束  
3. **部署基线**：YAML `guance.bindings` 仍可用；页面修改后以 Workspace 版本为准  

## 4. 后端取证方法（内部仍叫 evidence contract）

```http
GET /api/v1/troubleshooting/evidence/catalog
GET/PUT /api/v1/troubleshooting/evidence/assets
GET/PUT /api/v1/troubleshooting/evidence/contracts
GET /api/v1/troubleshooting/evidence/contracts/{contractRef}
GET/PUT/DELETE /api/v1/troubleshooting/evidence/routes
POST/GET /api/v1/troubleshooting/evidence/contract-trials
```

API Key、端点主机仍不进入配置页；查询模板不对 viewer 列表暴露。

## 5. 兼容

- 旧 `?tab=assets|routes` 进入目录时，自动跳到取证接入（接入系统）
- 旧 `?tab=acceptance` 进入目录时，打开数据源联调
- `?system=&service=` 可在取证接入页预选模块并打开模块工作台/预填登记表单
