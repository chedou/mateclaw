# 智能排障冒烟 CI 设计

## 目标

把现有 `scripts/troubleshooting-smoke.sh` 纳入持续集成，持续证明新检出的仓库可以在默认 H2 数据库和显式 `troubleshooting-demo` profile 下，通过八道 fail-closed 闸门产出一份 `LOCATED` 诊断。同时记录从代码检出完成到首次诊断的耗时，目标保持在五分钟内。

## 边界

- 只验证 Recorded Replay fixture 链路，不访问 Guance，不改变 `fixtureMode=true`。
- 复用现有 HTTP 冒烟脚本和 demo seeder，不新增第二套测试入口。
- 五分钟是可观测目标：超过时发出 CI warning，但首版不因机器抖动单独失败。
- 八道闸门、服务启动失败或超时必须使工作流失败。
- 不引入新的项目依赖。

## 方案

新增一个独立 GitHub Actions workflow，在影响智能排障运行链的 PR、`dev` 推送和手工触发时运行：

1. 检出代码并记录计时起点。
2. 配置 Temurin Java 21 和 Maven 缓存，通过仓库级 settings 固定使用阿里云公共镜像，
   确保 `curl`、`jq` 可用。
3. 先安装 `mateclaw-plugin-api`，再以 `dev,troubleshooting-demo` profile 后台启动 `mateclaw-server`。
4. 在有限时间内轮询登录接口，并确认 `csdp:903001` 已完成 demo 晋升；超时直接失败。
5. 使用 demo 管理员账号运行现有 `troubleshooting-smoke.sh`。
6. 把耗时和五分钟目标写入 GitHub Step Summary；超过目标时发 warning。
7. 无论成功失败都上传服务日志，并停止后台进程。

## 验证

新增一个无第三方依赖的 Shell 合同测试，检查 workflow 的触发范围、Java 版本、构建顺序、profile、有限等待、冒烟入口、耗时摘要和 `always()` 日志上传。先观察测试因 workflow 缺失而失败，再写最小 workflow 使其通过。

本地还执行：

- `bash -n scripts/troubleshooting-smoke.sh`
- `./scripts/troubleshooting-smoke.sh --gates`
- Maven 对 demo seeder 与固定回放套件的相关测试

完整 GitHub runner 行为只能在 Actions 环境最终验证；本地验证负责锁住静态合同和仓库内可执行部分。
