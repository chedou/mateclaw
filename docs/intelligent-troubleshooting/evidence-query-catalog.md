# 配置与接入：取证接入与查询规则说明书

> 状态：取证接入主线（2026-08-07）
>
> 配置入口按「系统 → 系统模块 → 可用工具 → 每项要配什么」组织；说明书只读，联调仍在工作台 overlay。

## 1. 分工

| 页面 | 路由 | 只做什么 |
|---|---|---|
| **取证接入** | `/troubleshooting/observability-assets` | 选系统/模块；看可用工具；按检查项登记范围、绑定规则、改路由、管理员只读试跑 |
| **查询规则说明书** | `/troubleshooting/evidence-catalog` | 只读浏览已审核规则的参数、返回字段与阻断点（侧栏不占主入口） |
| **排障规则库** | `/troubleshooting/sops` | 场景何时调用哪些工具（Playbook） |
| **数据源联调** | 工作台 `capability=guance` | 观测云端点/凭据与 owner 真源验收 |

## 2. 菜单位置

`智能排障 → 配置与接入`：

1. **取证接入**（主入口）
2. **排障规则库**

查询规则说明书从取证接入页工具栏进入。

## 3. 取证接入怎么配

对每个系统模块：

1. **系统模块范围**：环境、区域/集群/命名空间等
2. **平台数据源**：观测云是否就绪（未就绪则去联调）
3. **可用取证工具**：部署侧已审核的查询规则（如日志检索、链路还原…）

每条工具的检查项：

| 检查项 | 配置内容 |
|---|---|
| 启用并绑定查询规则 | 在模块配置里勾选已审核规则引用 |
| 登记 Workspace 模块资产 | 接管部署默认，写入可审计版本 |
| 填写工具所需资源参数 | 如 namespace、deployment、monitor_checker（按工具要求） |
| 声明取证路由 | 系统 × 信号种类 → 平台顺序（通常 guance） |
| 观测云数据源就绪 | 端点与凭据（数据源联调） |
| 管理员只读试跑 | 不创建排障单，不代表真源已验收 |

## 4. 后端合同不变

```http
GET /api/v1/troubleshooting/evidence/catalog
GET/PUT /api/v1/troubleshooting/evidence/assets
GET/PUT/DELETE /api/v1/troubleshooting/evidence/routes
POST/GET /api/v1/troubleshooting/evidence/contract-trials
```

仍不返回 API Key、端点主机、原始 DQL 或原始日志。

## 5. 兼容

- 旧 `?tab=assets|routes` 进入目录时，自动跳到取证接入
- 旧 `?tab=acceptance` 进入目录时，打开数据源联调
- `?system=&service=` 可在取证接入页预选模块并预填登记表单
