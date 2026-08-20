# MateClaw 测试环境部署手册

面向在一台 Linux 机器上用 Docker Compose 起一套 MateClaw 测试环境的运维/开发人员。
数据库使用**外部 MySQL**（生产同构），也附了内置 PostgreSQL 的走法。

本文所有事实均来自当前仓库源码，关键项在文末「事实来源」列了文件位置。
凡是本文没写的，就是没核实过的，不要照着猜。

> **验证到哪一步了。**
> - 编排合并：已用 Docker Compose v2.40.3 实测。MySQL 模式下 `!override` 确实移除了
>   PostgreSQL 健康门，合并结果只剩 `mateclaw-server` + `searxng`，环境变量注入正确；
>   PostgreSQL 模式不受影响。
> - 镜像构建与启动：已实测至 V217。服务端镜像可构建（约 1.23 GB），
>   容器起来后 `/actuator/health` 返回 `UP`。当前源码共 214 个 Flyway 迁移
>   （最高 V220）；V218–V220 已通过三方言形状与 H2 执行验证，
>   测试环境切换仍需按本文维护窗流程执行。
> - 外部 MySQL：已在 MySQL 8.0.11 上实测建表、H2 全量复制和本地应用启动；110 张业务表、
>   2,723 行数据逐表行数一致。测试服务器切换属于单独的环境验收，不能由本地结果代替。
>
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
| 版本 | **5.7 最低，8.0 推荐**；当前迁移已在 8.0.11 验证 |
| 字符集 | 库必须是 `utf8mb4` |
| 连通性 | 部署机能连到 MySQL 的端口 |
| 账号权限 | 目标库上的完整 DDL + DML（Flyway 每次启动都会检查当前 214 个迁移） |

**必须先手工建库，且显式指定字符集：**

```sql
CREATE DATABASE mateclaw CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'mateclaw'@'%' IDENTIFIED BY '你的强密码';
GRANT ALL PRIVILEGES ON mateclaw.* TO 'mateclaw'@'%';
FLUSH PRIVILEGES;
```

为什么不能省这一步：连接串带了 `createDatabaseIfNotExist=true`，看上去能自动建库。
当前迁移中仍有部分 `CREATE TABLE` 没有显式声明 `utf8mb4`，会继承库的默认字符集。
不同 MySQL 8 安装的服务端默认值可能被运维配置覆盖；手工建库并指定 utf8mb4，可以避免后续新表继承错误字符集。

（`application-mysql.yml` 里有条注释说"所有建表语句都显式指定 utf8mb4，不依赖 DB
默认字符集"——这条注释不准确，实测是 101/121。）

---

## 2. 部署步骤

### 2.0 从本地一键触发 Jenkins 发布

仓库自带 `scripts/release-test-env.sh`，用于触发既有
`mateclaw-troubleshooting-release` 任务、等待构建结束，并验收健康检查与排障页面。
Jenkins 用户和 API Token 只从当前终端环境读取，不会写入仓库：

```bash
export JENKINS_USER='你的 Jenkins 用户名'
export JENKINS_API_TOKEN='你的 Jenkins API Token'
./scripts/release-test-env.sh --allow-insecure-http
```

默认参数已对准当前测试环境：

```text
Jenkins: http://200.200.4.33:8080
Job:     mateclaw-troubleshooting-release
Site:    http://smartfix-sit.sangfor.com
```

当前 Jenkins 仍使用 HTTP。脚本默认拒绝在 HTTP 上发送 Basic 认证；只有确认处于可信内网时才显式放行：

```bash
./scripts/release-test-env.sh --allow-insecure-http
```

发布前会确认 Docker 构建输入是干净的 Git 检出，再将完整 Git SHA 作为
build arg 固化到镜像，并从 `/actuator/info` 反向验证。因此“未提交代码混入镜像”
或“Jenkins 成功但仍运行旧版本”都会直接报错，不会被当成发布成功。

Jenkins 的流水线定义随仓保存在 `Jenkinsfile.test-env`。任务会按以下顺序执行：

1. 从 GitHub 检出指定分支，并要求分支头与 `EXPECTED_COMMIT` 完全一致；
2. 从该提交构建带不可变版本号的 Docker 镜像；
3. 只读检查当前 Flyway 版本；无待执行迁移可直接继续，或只允许内容哈希完全一致的
   `V204 → V217` 基础包与 `V217 → V220` 正式排障包；
4. `DEPLOY` 才进入维护窗口：停止旧应用、生成 MySQL 逻辑备份，再启动新版本；
5. 新版本必须同时通过健康检查和精确版本校验，否则恢复旧容器并保留 MySQL 备份。

`scripts/release-test-env.sh` 默认发送 `ACTION=DEPLOY`、当前分支和当前完整 HEAD，
发布完成后再从外部域名复核健康、版本号和排障页面。要只构建镜像并检查迁移，可显式传入
`--parameter ACTION=VERIFY_ONLY`；脚本会自动跳过未切换站点的版本比对。

流水线不会让候选容器提前连接正在服务的 MySQL，也不会自动执行破坏性的数据库恢复。
本任务通常只允许数据库已无待执行迁移时发布应用。已审核两个连续例外：

- `V204 → V217`：13 个 Agent Team 基础迁移；
- `V217 → V220`：3 个正式排障迁移，增加评估样本来源字段、正式诊断 claim 表和通用正式调查的来源验收审计字段。

两个包的文件范围和组合 SHA-256 均在 `Jenkinsfile.test-env` 冻结。可从 V204
一次升至 V220，也可从 V217 只执行 V218–V220；V218/V219 中断后只允许继续完成同一审核包。
任一文件内容、数量、起点或终点变化都会 fail-closed。切换前的完整逻辑备份
保留在 `/opt/mateclaw/releases`。其他 schema 升级必须先走独立数据库维护窗口。

合并 `dev` 后，Agent Team 迁移从重叠的 V172–V184 重新编号为 V205–V217。健康检查会同时
要求排障域和 Agent Team 两个根表存在；如果目标库曾单独跑过 `dev` 的另一条 V172 谱系，
`/actuator/health` 会保持 `DOWN`，脚本不会把 Flyway 自动 repair 后的不完整 schema 当成发布成功。

需要发布其他分支时，显式传入分支；版本 SHA 仍默认取本地 HEAD：

```bash
./scripts/release-test-env.sh \
  --allow-insecure-http \
  --parameter BRANCH=claude/intelligent-troubleshooting-design
```

数据库密码、`MATECLAW_SETTING_KEY` 和观测云密钥继续只保存在 Jenkins Credentials
或部署机的 mode-600 `/opt/mateclaw/mateclaw.env` 中，不进入参数、构建日志或仓库。

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
- `--base-url`：**别人在浏览器地址栏里看到的那个地址**，域名和 IP 都可以

#### `--base-url` 到底填什么

填**用户实际访问用的 origin**，不是容器地址，也不是内网回环地址。域名完全可以，而且有域名就该用域名：

| 场景 | 填什么 |
|---|---|
| 直接用 IP 访问 | `http://10.0.0.5:18080` |
| 有域名，直连 18080 | `http://mateclaw-test.example.com:18080` |
| 域名 + nginx 反代 + TLS | `https://mateclaw-test.example.com`（不带端口） |
| 只在本机测 | `http://localhost:18080` |

注意最后两种的区别：反代场景下端口是 nginx 的 443，不是容器的 18080，所以**不能带端口**。
填错的典型症状是页面能打开，但企微里收到的排障链接点开是 404 或连不上。

它会写进三个配置项：

| 配置项 | 影响 |
|---|---|
| `MATECLAW_PUBLIC_BASE_URL` | 生成文件的下载绝对链接、IM 通道回链 |
| `MATECLAW_TROUBLESHOOTING_WORKBENCH_BASE_URL` | 排障深链 `{base}/troubleshooting?diagnosisId=…` |
| `MATECLAW_CORS_ALLOWED_ORIGINS` | 浏览器跨域白名单 |

**为什么不能让程序自己猜？** 不配的话服务端只能回落到请求的 Host 头。在反代后面那就是内网地址，
页面上看着没问题——因为浏览器本来就在那个页面上；但一旦链接离开浏览器（发进企微、被复制转发），
收到的人就打不开了。所以凡是要"活着离开浏览器"的链接，都必须有一个显式的绝对地址。

脚本会从 `.env.example` 生成 `.env`，自动填入随机的 `JWT_SECRET`、`SEARXNG_SECRET`
和 `MATECLAW_SETTING_KEY`，然后**主动停下来**，提示你补 MySQL 账号密码。这是有意为之：
这两个值必须匹配你服务器上已存在的账号，脚本编一个随机密码只会生成一份看起来很像样、
实际登不上的配置。

#### `MATECLAW_SETTING_KEY` 要备份

它是落库凭据的加密口令（AES-256-GCM），保护公众号 `app_secret`、**观测云 API Key**
这类存在数据库里的密钥。

- **留空不会报错**，但会回落到编译进镜像的默认口令——那把钥匙每套安装都一样、且随代码公开，
  只算混淆不算保护。所以脚本在新建 `.env` 时直接给你生成一个。
- **它是解开已有密文的唯一钥匙。** 换掉或丢了，库里已存的密钥就再也读不出来，只能逐个重填。
  所以请连同 `.env` 一起备份。
- 如果你的 `.env` 是在这次改动之前生成的（里面没有这一项），脚本**不会**帮你补，只会警告。
  这是刻意的：库里如果已经存了密文，写进一把新钥匙等于把它们全部作废。空库随时可以补上，
  用过的库要先想清楚。

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

### 2.5 从现有 H2 全量迁移到 MySQL

`up` 只会让 Flyway 创建 MySQL 表，不会自动搬运 H2 里的用户、Workspace、模型配置和排障记录。
已经使用过 H2 的环境必须做一次停机全量迁移。迁移会让 MySQL 的全部业务表与 H2 快照完全一致，
所以只能指向本应用的专用 schema，并且必须先备份目标库。

1. 停掉仍在写 H2 的 MateClaw，复制 `mateclaw.mv.db`（以及存在时的
   `mateclaw.trace.db`）到带时间戳的备份目录。迁移只读这份静止快照，不读运行中的文件。
2. 先以 `mysql` profile 启动一次目标版本，让 Flyway 在目标 schema 建好同版本结构；健康后再停掉应用。
3. 用 `mysqldump --defaults-extra-file=<mode-600-client.cnf>` 备份整个目标 schema。不要把密码写在命令参数里。
4. 准备 H2 与 MySQL JDBC driver，并在当前终端安全读入密码：

   ```bash
   read -rsp 'MySQL password: ' MATECLAW_MIGRATION_DB_PASSWORD
   export MATECLAW_MIGRATION_DB_PASSWORD
   printf '\n'
   ```

5. 先只读核对结构和行数。`<h2-snapshot>` 不带 `.mv.db` 后缀：

   ```bash
   java --class-path '<h2.jar>:<mysql-connector-j.jar>' scripts/h2-to-mysql.java \
     'jdbc:h2:file:<h2-snapshot>;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE' \
     'jdbc:mysql://<host>:<port>/<db>?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai' \
     '<user>' verify '<db>'
   ```

   表或字段集合只要有一处不一致，工具就会 fail closed，不允许进入复制。MySQL 自动生成列仍参与
   结构核对，但不会被显式写入；MySQL 会依据同版本定义重新计算。H2 的二进制 JSON 值只在目标列
   确认为 `JSON` 时转成 UTF-8 文本，普通二进制列保持原样。

6. 确认 H2 快照和 MySQL dump 都可恢复后，显式打开一次性替换闸门并执行复制：

   ```bash
   MATECLAW_MIGRATION_ALLOW_REPLACE=true \
   java --class-path '<h2.jar>:<mysql-connector-j.jar>' scripts/h2-to-mysql.java \
     'jdbc:h2:file:<h2-snapshot>;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE' \
     'jdbc:mysql://<host>:<port>/<db>?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai' \
     '<user>' copy '<db>'
   ```

   工具会在一个事务里清空并重写所有业务表（包括源端为 0 行的表），不复制
   `flyway_schema_history`，结束时逐表反查行数；任何不一致都会返回失败。

7. 清掉终端变量 `unset MATECLAW_MIGRATION_DB_PASSWORD`，再以
   `SPRING_PROFILES_ACTIVE=mysql,csdp-guance-evidence-pilot` 启动 MateClaw。第二个
   profile 只是显式打开通用 Guance 只读调查所需的服务端路由和预算；
   场景级正式通道仍会在 D20 完成前 fail-closed。
   除健康检查外，至少核对登录、Workspace、模型配置、排障单和观测云连接配置。

回滚时先停应用，恢复迁移前的 MySQL dump；若决定回到 H2，则恢复 H2 快照并撤掉
`SPRING_PROFILES_ACTIVE=mysql,csdp-guance-evidence-pilot`。在两种数据库之间反复来回启动会产生双写分叉，不属于受支持流程。

### 2.6 关于多机部署

先区分两种"多机"，它们的答案完全不同。

**应用和数据库分开在两台机器上** —— 这正是本文的默认场景，MySQL 在别的机器上，
`--db-host` 指过去就行，`--base-url` 填应用那台的访问地址。没有任何额外问题。

**跑多个应用实例、前面挂负载均衡** —— **测试环境请只起一个实例。**
这套系统目前只是部分支持横向扩展，以下几处状态是节点本地的：

| 组件 | 问题 |
|---|---|
| 企微通道 | `WeComChannelAdapter.requiresSingleLeader()` 返回 `true`，且入站 `req_id` 存在节点内存的 `ConcurrentHashMap` 里。只有持有该上下文的那个节点能回消息 |
| `/app/data` 文件 | 上传、生成文件、聊天附件落在各自节点的卷里，另一个节点读不到 |
| 取证会话注册表 | 未命中路 Agent 的工具会话在内存里，不跨节点 |
| Webchat | 应用内文档明确写着当前是单实例，多实例在路线图上 |

已经做好的部分是：REST 鉴权是无状态 JWT（`SessionCreationPolicy.STATELESS`），轮询转发没问题；
通道 leader 用 ShedLock 选举；排障的运行键用数据库租约领取。
也就是说**底子是往多实例走的，但还没走完**。真要多实例，至少得先解决共享存储和会话粘滞，
那超出测试环境的范围。

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
结论的地方。（排障开关现在已改为数据库配置，见 §5.2；这里保留转发是为了让首次部署仍有
一个可用的默认值，以及让 `MATECLAW_SECURITY_SSRF_ALLOWLIST` 这类部署级边界能生效。）

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

### 5.2 智能排障模块开关（默认全关，页面上改）

**这几个开关现在存在数据库里，按 Workspace 生效，不需要改 `.env`、也不需要重启。**

入口：**智能排障 → 更多配置 → 数据连接**，页面顶部的「数据源连接设置」卡片。需要
`manage:troubleshooting` 能力，也就是 workspace **admin 及以上**；普通成员看不到这张卡。

| 页面上的项 | 默认 | 含义 |
|---|---|---|
| 观测云（Guance） | 关 | 真源取证总开关 |
| 观测云 API 地址 | 空 | 只填到域名和端口，不带查询路径 |
| 观测云 API Key | 空 | 只写不读，见下 |
| 允许 http 明文端点 | 关 | 仅内网确无 TLS 时使用，平时不显示 |
| 受控回放（Recorded Replay） | 关 | fixture 样本，非真实数据 |
| 未命中路 Agent | 关 | OPEN_DISCOVERY 兜底调查 |

几点需要知道：

- **API Key 只写不读。** 存进去就用 AES-256-GCM 加密，页面只回显 `****a1b2` 这样的尾号提示。
  改地址时把密钥框留空就是"保持不变"；要清除得点那个显式的清除按钮。这样改个地址不会顺手把凭据抹了。
- **内网地址还要过出站白名单。** 页面允许 admin 选地址，但一个内网/私网主机能不能被访问，
  仍由部署环境的 `MATECLAW_SECURITY_SSRF_ALLOWLIST` 决定（逗号分隔，支持主机名、IP、IPv4 CIDR）。
  没加进白名单的私网地址在保存时就会被拒。这是有意的两把钥匙：**Workspace 决定用哪个地址，
  部署决定内网里有哪些地址存在**，避免一个 admin 账号被盗就能把取证请求指向任意内网服务。
- **改地址会让已有的 T7 验收失效**，需要 owner 重新验收，这是刻意的：端点变了，之前那次核实就不算数了。
- 每次保存都会写审计（谁、什么时候、改了什么、变更说明），但**不记录密钥本身**。
- 并发保存有乐观锁。两个人同时改，后提交的会被拒绝并提示重新载入，而不是悄悄覆盖对方的密钥。

仍然留在 `.env` 里的只有这两个：

| 环境变量 | 默认 | 为什么不在数据库 |
|---|---|---|
| `MATECLAW_TROUBLESHOOTING_AGENT_ID` | `0` | 已有独立的"数字员工绑定"页面管理 |
| `MATECLAW_SECURITY_SSRF_ALLOWLIST` | 空 | 部署级安全边界，不能让被管理的一方自己放开 |

数据库里没有该 Workspace 的记录时，页面显示的是 `.env` 里的值，标记为「继承部署默认值」；
第一次保存后就转为「本 Workspace 配置」，之后不再跟随 `.env` 变化。也就是说**老部署不改任何东西，
行为和以前完全一致**。

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
13. 启动日志里**没有** `[SettingCrypto] No MATECLAW_SETTING_KEY set`：
    `./scripts/deploy-test-env.sh logs | grep SettingCrypto`
    有这条就说明落库密钥在用公开的默认口令，回到 §2.2
14. 排障配置卡可用：用 admin 打开「智能排障 → 更多配置 → 数据连接」，
    确认顶部出现「数据源连接设置」，且标着「继承部署默认值」

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
