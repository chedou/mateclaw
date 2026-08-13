# MateClaw 测试环境部署手册

面向在一台 Linux 机器上用 Docker Compose 起一套 MateClaw 测试环境的运维/开发人员。
数据库使用**外部 MySQL**（生产同构），也附了内置 PostgreSQL 的走法。

本文所有事实均来自当前仓库源码，关键项在文末「事实来源」列了文件位置。
凡是本文没写的，就是没核实过的，不要照着猜。

> **未经端到端验证。** 编写本文的环境没有可用的 Docker 守护进程，因此镜像构建与
> 容器编排逻辑只做过语法与配置层面的检查，没有真实跑通过一次完整部署。
> 首次部署请按第 7 节逐步确认，遇到与本文不符的现象以实际输出为准。

---

## 1. 前置条件

### 部署机

| 项目 | 要求 | 说明 |
|---|---|---|
| Docker Engine | 20.10+ | 需带 compose 插件 |
| Docker Compose | **v2.24+** | MySQL 模式必须；低于此版本无法移除 PostgreSQL 依赖门，见 §3.2 |
| 内存 | 8 GB 起 | 4 GB 能跑但很紧 |
| 磁盘 | 20 GB 起 | 运行时基础镜像是 Playwright（自带三套浏览器），本身就好几个 GB |
| CPU | 2 核起，4 核舒适 | |
| 出网 | 需要 | 拉基础镜像、Maven 依赖、npm 依赖；调用 LLM 也要出网 |

检查版本：

```bash
docker --version
docker compose version    # 必须 >= v2.24
```

**构建机内存是独立的坑。** 前端构建阶段固定申请 6 GB Node 堆（Rollup 在更低配额下会被
OOM killer 杀掉），所以构建机至少 8 GB 内存。如果部署机比这小，就在大机器上
构建并推镜像，不要在小机器上直接 build。

### MySQL 服务器

| 项目 | 要求 |
|---|---|
| 版本 | **5.7 最低，8.0 推荐** —— 有 12 个迁移脚本用了 JSON 列类型 |
| 字符集 | 库必须是 `utf8mb4` |
| 连通性 | 部署机能连到 MySQL 的端口 |
| 账号权限 | 目标库上的完整 DDL + DML（Flyway 每次启动都会跑 196 个迁移） |

**必须先手工建库，且显式指定字符集：**

```sql
CREATE DATABASE mateclaw CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'mateclaw'@'%' IDENTIFIED BY '你的强密码';
GRANT ALL PRIVILEGES ON mateclaw.* TO 'mateclaw'@'%';
FLUSH PRIVILEGES;
```

为什么不能省这一步：连接串带了 `createDatabaseIfNotExist=true`，看上去能自动建库。
但 196 个迁移里 121 条 `CREATE TABLE` 只有 101 条显式写了 `utf8mb4`，**剩下 20 张表
继承库的默认字符集**。MySQL 8 服务端默认就是 utf8mb4，自动建库碰巧没事；MySQL 5.7
默认 latin1，那 20 张表会在写中文时静默出错。手工建库一次，这个问题就不存在了。

（`application-mysql.yml` 里有条注释说"所有建表语句都显式指定 utf8mb4，不依赖 DB
默认字符集"——这条注释不准确，实测是 101/121。）

---

## 2. 部署步骤

### 2.1 取代码

```bash
git clone <仓库地址> mateclaw
cd mateclaw
```

### 2.2 首次运行，生成配置

```bash
./scripts/deploy-test-env.sh up --db mysql \
  --db-host 10.0.0.9 \
  --base-url http://10.0.0.5:18080
```

- `--db-host`：MySQL 服务器地址
- `--base-url`：**别人在浏览器里访问这套系统的地址**。不是 localhost，除非只在本机用。
  它会同时写入三个配置项（公开基址、排障工作台基址、CORS 白名单）

脚本会从 `.env.example` 生成 `.env`，自动填入随机的 `JWT_SECRET` 和 `SEARXNG_SECRET`，
然后**主动停下来**，提示你补 MySQL 账号密码。这是有意为之：这两个值必须匹配你服务器上
已存在的账号，脚本编一个随机密码只会生成一份看起来很像样、实际登不上的配置。

### 2.3 填 MySQL 账号

编辑 `.env`：

```properties
DB_HOST=10.0.0.9
DB_PORT=3306
DB_NAME=mateclaw
DB_USERNAME=mateclaw
DB_PASSWORD=你在 2.1 里设的密码
```

### 2.4 正式启动

```bash
./scripts/deploy-test-env.sh up
```

第二次不用再带参数——`DB_ENGINE=mysql` 已经记在 `.env` 里了，之后所有命令都会自动
用 MySQL 那套编排，也不会误操作到 PostgreSQL 栈。

启动过程：

1. 前置检查（Docker、compose、openssl、内存）
2. 校验 MySQL 配置已填，并且不是 `.env.example` 里的占位符
3. **校验合并后的编排确实不再等待 PostgreSQL**（见 §3.2）
4. 构建镜像 —— **首次会拉几个 GB 的 Playwright 基础镜像，慢，属正常**
5. 起容器，轮询健康检查，最多等 5 分钟

看到 `[deploy] server is UP` 就成了。

---

## 3. 这套编排是怎么拼的

### 3.1 文件分工

| 文件 | 作用 |
|---|---|
| `docker-compose.yml` | 基础栈：postgres + searxng + mateclaw-server |
| `docker-compose.test.yml` | 测试环境覆盖，**与数据库无关**，两种模式共用 |
| `docker-compose.mysql.yml` | 切到外部 MySQL |
| `docker-compose.pg-test.yml` | 仅 PostgreSQL 模式：把库暴露到 127.0.0.1 便于 psql |

实际命令（脚本已封装，一般不用手敲）：

```bash
# MySQL
docker compose -f docker-compose.yml -f docker-compose.mysql.yml -f docker-compose.test.yml up -d

# 内置 PostgreSQL
docker compose -f docker-compose.yml -f docker-compose.test.yml -f docker-compose.pg-test.yml up -d
```

### 3.2 为什么 MySQL 模式要求 Compose v2.24+

基础编排里，服务端被 `depends_on: postgres: condition: service_healthy` 卡着。
用了外部 MySQL 就没有这个 PostgreSQL 容器，这道门永远不会通过。

而 `depends_on` 是映射结构，Compose 合并映射时**只能加键和覆盖键，没法减键**——
普通的覆盖文件删不掉它。只能用 `!override` 标签整体替换整个属性，这个标签 v2.24 才有。

老版本 Compose 遇到它可能直接报 unknown tag（这还算好，至少响），也可能忽略掉，
那就会留下一道永远等不到的门，表现为容器一直卡在启动中。所以脚本在动手之前会跑一次
`docker compose config`，**实际检查合并结果里那道门是不是真的没了**，而不是去比对版本号。
检查不过就报错退出并告诉你升级。

`postgres` 服务本身删不掉，就挂在一个永远不会激活的 profile 后面，Compose 会跳过它。
它没有 `build:`，构建阶段也不产生开销。

### 3.3 测试覆盖做了什么

**补上基础编排漏掉的环境变量。** `.env.example` 里记录了 `MATECLAW_PUBLIC_BASE_URL`、
`MATECLAW_OPENAPI_EXPOSE_UI` 和整个 `MATECLAW_TROUBLESHOOTING_*` 段落，但
`docker-compose.yml` 根本没有把它们传进容器。也就是说**在 Docker 下往 `.env` 里写这些值
是完全没有效果的**，而测试环境恰恰是最可能有人去打开这些开关、然后得出"这功能是坏的"
结论的地方。

**固定堆大小。** 仓库里没有任何地方设 `-Xmx`，JVM 默认吃宿主机内存的 25%。同一个镜像在
8 GB 和 64 GB 机器上行为完全不同。覆盖文件用 `mem_limit` 配合 `MaxRAMPercentage`，
让内存成为配置的属性而不是硬件的属性。默认容器限 6 GB、堆占 45%（约 2.7 GB），
剩下的留给 2 GB 的 `/dev/shm`（Chromium 要用）和 JVM 堆外内存。

可通过 `.env` 调整：`TEST_MEM_LIMIT`、`TEST_CPUS`、`TEST_JVM_MAX_RAM_PERCENT`。

---

## 4. 首次登录与必做的安全处理

访问 `http://部署机IP:18080`。

| | |
|---|---|
| 用户名 | `admin` |
| 密码 | `admin123` |

**这是硬编码在种子数据里的默认口令，请第一时间改掉。** 尤其测试环境往往在内网可达，
默认口令等于没有口令。改密入口在页面右上角的用户菜单里（不是"设置 → 安全"，
应用内文档那句话是过时的）。

改了会不会被重置回去？不会。种子只在 `mate_user` 表为空时执行一次，之后启动都会跳过。

### 端口

| 宿主端口 | 容器端口 | 用途 |
|---|---|---|
| 18080 | 18088 | Web UI + API（前端已打进 jar，同端口） |
| 1455 | 1455 | OpenAI OAuth 回调，不用可忽略 |

---

## 5. 起来之后还要配什么

### 5.1 LLM 密钥（必配，否则模型相关功能都不可用）

**LLM 密钥不是环境变量，是存在数据库里的。** 在 UI 里配：`设置 → 模型管理`（`/settings/models`）。

一个例外要知道：`DASHSCOPE_API_KEY` 这个环境变量对通义千问是有兜底作用的——数据库里没配
的时候会回落到它。其他厂商没有这种兜底。所以"环境变量完全无效"这个说法只对非 DashScope
成立。

另外，全新库并不是"一个供应商都没有"——种子数据会插入一批供应商记录，只是 `api_key` 为空、
处于禁用状态，需要你填 key 并启用。

### 5.2 智能排障模块开关（默认全关）

| 环境变量 | 默认 | 含义 |
|---|---|---|
| `MATECLAW_TROUBLESHOOTING_GUANCE_ENABLED` | `false` | 观测云真源取证 |
| `MATECLAW_TROUBLESHOOTING_GUANCE_BASE_URL` | 空 | 观测云地址 |
| `MATECLAW_TROUBLESHOOTING_GUANCE_API_KEY` | 空 | 观测云密钥 |
| `MATECLAW_TROUBLESHOOTING_REPLAY_ENABLED` | `false` | 录制回放（fixture，非真实数据） |
| `MATECLAW_TROUBLESHOOTING_AGENT_ENABLED` | `false` | 未命中路 Agent |
| `MATECLAW_TROUBLESHOOTING_AGENT_ID` | `0` | 专用 Agent 的 ID |

改 `.env` 后需要重启：`./scripts/deploy-test-env.sh up`。

**重要：把开关打开不等于取到的证据就可信。** 在 workspace owner 完成观测云
measurement / 字段契约核实之前，`fixtureMode` 恒为 true，结论不具备真实性。
详见 `docs/intelligent-troubleshooting/HANDOFF.md`。

---

## 6. 日常运维命令

```bash
./scripts/deploy-test-env.sh status              # 容器状态 + 健康检查
./scripts/deploy-test-env.sh logs                # 跟踪服务端日志
./scripts/deploy-test-env.sh logs searxng        # 指定服务
./scripts/deploy-test-env.sh down                # 停止，保留数据
./scripts/deploy-test-env.sh reset               # 删卷，需输入 RESET 确认
./scripts/deploy-test-env.sh --help
```

### `reset` 在 MySQL 模式下的语义

只删本地 `server_data` 卷（上传文件、skills 等），**绝不碰你的 MySQL 服务器**。
要清库自己去 drop —— 脚本不会对一台它并不拥有的服务器做破坏性操作。

### `rotate-db-password`

只对内置 PostgreSQL 有效，MySQL 模式会直接拒绝。你的 MySQL 密码在你自己的服务器上改，
改完更新 `.env` 里的 `DB_PASSWORD` 再 `up`。

### 数据都存在哪

| 位置 | 内容 | `down -v` 后 |
|---|---|---|
| 外部 MySQL | 全部业务数据 | **保留**（不归 compose 管） |
| `server_data` 卷 | `/app/data`：skills、wiki 上传、生成文件、聊天附件 | 丢失 |
| 容器文件系统 | `~/.mateclaw/plugins`、临时文件 | 容器重建即丢失 |

注意最后一行：插件目录不在卷里，容器重建就没了。测试环境装过的插件要重装。

---

## 7. 首次部署验收清单

按顺序确认，任一步不过就先停下来看日志：

1. `docker compose version` ≥ v2.24
2. 在部署机上能连通 MySQL：`mysql -h <IP> -P 3306 -u mateclaw -p -e 'SELECT 1'`
3. 库存在且是 utf8mb4：
   `SELECT default_character_set_name FROM information_schema.SCHEMATA WHERE schema_name='mateclaw';`
   → 期望 `utf8mb4`
4. `./scripts/deploy-test-env.sh up` 输出 `verified: the merged model no longer waits on PostgreSQL`
5. 输出 `[deploy] server is UP`
6. Flyway 跑完且无报错：`./scripts/deploy-test-env.sh logs | grep -i flyway`
7. 表已建好：`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='mateclaw';`
8. 浏览器能打开 `http://部署机IP:18080`
9. `admin` / `admin123` 能登录
10. **改掉默认密码**
11. 中文没乱码：随便建个带中文名的对象，刷新后确认显示正常
12. 在 `设置 → 模型管理` 配一个 LLM key，发一条消息验证能通

---

## 8. 常见故障

| 现象 | 原因与处理 |
|---|---|
| `unknown tag !override` | Compose 低于 v2.24，升级 compose 插件 |
| 提示仍在等待 postgres | 同上，脚本已拦截并给出提示 |
| `Access denied for user` | MySQL 账号密码不对，或没授权从部署机 IP 连接（`'user'@'%'`） |
| `Unknown database 'mateclaw'` | 没建库，回到 §1 |
| 中文变问号 | 库不是 utf8mb4。改库字符集救不了已建的表，最省事是 drop 库重建再重跑 |
| 构建阶段被 OOM 杀掉 | 构建机内存不足 8 GB，换机器构建 |
| 健康检查 5 分钟不过 | `logs` 看日志。多数是数据库连不上或 Flyway 失败 |
| 拉镜像超时 | 配置 registry mirror，或在能出网的机器上构建后推送 |
| CORS 报错 | `--base-url` 填的不是用户实际访问的地址 |

### 关于健康检查

脚本轮询的是 `http://127.0.0.1:18080/actuator/health`，这个路径**不需要鉴权**
（安全配置只对 `/api/**` 强制认证，其余放行）。

不要用应用内文档里那条 `curl /api/v1/system/health` —— 那个在 `/api/**` 下面，
不带 token 会返回 401。文档那段是过时的。

---

## 9. 内置 PostgreSQL 模式（备选）

如果生产不是 MySQL，或者只想先跑起来看看，用内置 PostgreSQL 更省事——库跟着 compose 一起起，
不需要 IP、不需要开防火墙、不需要找 DBA：

```bash
./scripts/deploy-test-env.sh up --base-url http://10.0.0.5:18080
```

不带 `--db` 就是 PostgreSQL 模式。所有密码自动生成，`.env` 不用手工改任何东西。
`reset` 就是删卷，一秒回到干净状态。

这个模式下 `rotate-db-password` 可用，专门解决"改了 `.env` 里的 `DB_PASSWORD` 就连不上"
——因为建角色的初始化脚本只在数据目录为空时跑一次，之后角色一直用最初那个密码。

---

## 10. 事实来源

| 事实 | 位置 |
|---|---|
| 默认管理员 `admin` / `admin123` | `mateclaw-server/src/main/resources/db/data-mysql-zh.sql` |
| 种子仅在 `mate_user` 为空时执行 | `vip/mate/config/DatabaseBootstrapRunner.java` `isDataAlreadySeeded()` |
| 容器端口 18088，宿主 18080 | `application.yml`；`docker-compose.yml` |
| 前端打进 jar 同端口提供 | `mateclaw-server/Dockerfile`；`SpaForwardController.java` |
| actuator 免鉴权 | `vip/mate/config/SecurityConfig.java`（`/api/**` 认证，其余 permitAll） |
| LLM key 存 `mate_model_provider` | `docker-compose.yml` 注释；`ModelProviderEntity.java` |
| DashScope 环境变量兜底 | `application.yml`；`DashScopeChatModelBuilder.java` |
| 排障开关默认 false | `application.yml` `mateclaw.troubleshooting.*` |
| MySQL profile 读的 DB_* 变量 | `application-mysql.yml` |
| 前端构建 6 GB Node 堆 | `mateclaw-server/Dockerfile` `NODE_OPTIONS` |
| `shm_size: 2gb` | `docker-compose.yml` |
| 101/121 建表语句显式 utf8mb4 | `db/migration/mysql/` 全量统计 |
| 12 个迁移使用 JSON 列 | 同上 |

### 与仓库内既有文档不一致之处

写这份手册时核实出以下几条，应用内文档 `docs/zh|en/docker-deploy.md` 及部分代码注释
与实际代码不符，本文以代码为准：

1. 健康检查示例用 `/api/v1/system/health` 且不带 token —— 实际会 401
2. 改密路径写"设置 → 安全" —— 实际在页面右上角用户菜单
3. "LLM 环境变量完全无效" —— DashScope 有兜底
4. "全新安装没有任何供应商" —— 种子会插入多条供应商记录
5. `application-mysql.yml` 注释称所有建表语句都指定 utf8mb4 —— 实际 101/121
6. `application.yml` 注释称 Swagger 始终公开 —— 已被 `mateclaw.openapi.expose-ui` 收紧
7. `MATECLAW_TROUBLESHOOTING_AGENT_EXTRA_PERMITTED_PLATFORMS` 在注释里被当作可用开关 ——
   YAML 里没有对应绑定，这个变量目前不起任何作用
