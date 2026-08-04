# 取证查询目录 · 方案 C 页面 + 方案 A 后端

> 状态：已实现（2026-08-04）

## 1. 目标

把“不同系统、不同模块出现问题时，需要向观测平台查什么、传什么、返回什么”变成一个可发现、可核对、可维护的产品入口，而不是继续散落在 YAML、DQL 和代码里。

目录采用以下层级：

```text
Workspace → 系统 → 模块 → 排障场景 → 证据维度 → 查询合同 → 来源适配器
```

其中：

- 页面以系统、模块和排障场景组织内容，回答开发首先关心的“我要查什么”；
- 后端是服务端拥有的最小查询合同目录，回答运行时“允许传什么、从哪里取、规范化返回什么”；
- 目录只描述合同和当前状态，不直接查询 Guance，不保存或下发 API Key、端点主机、原始 DQL、原始日志。

## 2. 产品入口

- 菜单：`智能排障 → 更多能力 → 取证查询目录`
- 路由：`/troubleshooting/evidence-catalog`
- 权限：`manage:troubleshooting`

页面分四个工作区：

1. **系统与模块**：按系统、模块、场景浏览合同；
2. **查询合同**：统一检索场景、信号、合同和规范输出；
3. **路由与绑定**：维护 `系统 + 证据维度 → 有序适配器`；
4. **联调与验收**：查看端点、凭据、查询绑定和 owner 验收状态，以及当前阻断原因。

## 3. 后端合同

目录接口：

```http
GET /api/v1/troubleshooting/evidence/catalog
X-Workspace-Id: <workspace>
```

返回内容由当前发布物中的 server-owned 查询绑定、Workspace 取证路由和现有 Guance 就绪/验收能力合成。每份查询合同至少投影：

- 系统、模块、场景、待回答问题；
- `signalKind`、查询绑定名和适配器路由；
- 请求方式、路径、`qtype` 和查询预算；
- 运行时参数及其来源、服务端固定条件；
- canonical 输出字段；
- 绑定状态、是否可运行和具体阻断原因。

路由维护继续复用既有接口：

```http
GET    /api/v1/troubleshooting/evidence/routes
PUT    /api/v1/troubleshooting/evidence/routes
DELETE /api/v1/troubleshooting/evidence/routes?system={system}&signalKind={signalKind}
```

语义保持不变：Workspace 声明优先于部署默认；显式保存空平台列表代表禁用，删除声明才恢复部署默认。

## 4. 首版编辑边界

首版只允许在页面维护 Workspace 取证路由，不允许在线编辑 DQL、端点或凭据。这是有意保留的安全和发布边界：

- DQL 和字段映射仍由代码评审、测试和发布过程治理；
- 端点与 API Key 仍由运维环境注入；
- Workspace 只能在部署已启用的只读来源中选择路由；
- 目录接口经过脱敏，并有测试保证序列化结果不出现 DQL、API Key 或 base URL。

后续只有在真实系统接入证明“在线编辑查询合同”是阻塞点后，才引入版本化草稿、校验、试跑、审批和回滚能力；不能直接把自由文本 DQL 编辑器接到生产查询路径。

## 5. 当前覆盖与扩展方式

当前 `csdp-guance-evidence-pilot` 提供会话消息发送失败场景的三份合同：

- `log_search`：失败日志检索；
- `log_trace_bundle`：按 PS ID 还原链路；
- `contrast_sample`：成功/失败样本对照。

`csp-clouddial-pilot` 提供 `synthetic_probe` 合同。某个 Profile 或绑定未在当前部署启用时，页面不会伪装成已接入；它只展示当前服务端实际装配出的系统和合同。

接入新系统时，先在 server-owned binding 中补齐系统、模块、场景、问题和查询合同，再由 Workspace 在页面声明路由，最后完成真实字段核对与 owner 验收。目录是接入控制面，不替代 Evidence Router、Adapter 或 Evidence Spine。
