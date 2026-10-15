# 验证记录

首次验证日期：2026-10-14；提交前复验日期：2026-10-15。环境：macOS arm64、Temurin JDK 17.0.19、Maven 3.9.16、PostgreSQL 17.11、Spring Boot 4.1.1。

2026-10-15 从正式提交清单复制 36 个文件到独立临时目录，重新构建并运行完整验证与数据库断连实验。37 项测试全部通过，两条验证命令均以 0 退出；应用崩溃恢复和数据库断连恢复均通过。以下单元/集成测试与这两个进程实验的结果已在本次复验中确认；中断清理、缺失 JAR、模拟供应商各端点和原问题的修复前对照实验记录来自 2026-10-14。

完整命令：`bash scripts/verify.sh -s .local/maven-settings.xml`。本机直连 Maven Central 存在 DNS 超时，因此验证使用本地 Maven 镜像配置；该配置不包含凭据、不属于运行依赖，也不要求其他机器复用。

## 复现路径

准备 JDK 17、Maven、Python 3 和原生 PostgreSQL 17 后，在仓库根目录运行：

```bash
bash scripts/verify.sh
python3 scripts/verify_storage_outage.py
```

第一条命令在未设置 TEST_DB_URL 时创建临时数据库，运行单元与集成测试，并启动打包应用验证强制终止后的恢复；若显式设置 TEST_DB_URL，则必须使用可清空的测试数据库。第二条始终使用自己创建的临时数据库，验证停止/重启数据库后的接口行为；即使环境中设置了 TEST_DB_URL，也不会操作该地址。两条命令正常结束时停止自己启动的进程并清理临时数据。PG_BIN 可指定 PostgreSQL 工具目录，Maven 镜像参数按 README 透传。

第一条正常退出时，测试报告合计 37 项（16 项单元测试、21 项集成测试），最后输出 `packaged_app=passed`、`crash_recovery=passed`，本次运行观察到供应商收到 2 次。第二条正常退出时输出 `storage_outage=passed`、`offline_post=503`、`offline_get=503`、`same_key_retry=200`。详细应用日志保存在 target 目录。仅执行 `mvn test` 会运行单元测试，不包含数据库集成测试和进程实验。

| 建议观察的场景 | 实际检查位置 | 可以证明什么 |
| --- | --- | --- |
| 多个调用方提交，相同 request_id 重复提交 | `NotificationApiIT` | 调用方隔离、同键去重、不同内容冲突，受理失败不会伪装成 202 |
| 供应商暂时失败、永久失败和超时 | `DeliveryIT`、`RetryPolicyTest` | 分类重试、退避和预算边界；超时可能发生在供应商已收到之后 |
| 供应商收到请求后强制终止应用，再启动 | [smoke.py](../scripts/smoke.py) | 实际进程恢复、租约过期、旧尝试 unknown 和可能重复送达 |
| 运行中停止数据库，再恢复 | [verify_storage_outage.py](../scripts/verify_storage_outage.py) | 统一脱敏 503、原受理任务保留、恢复后同键重试 |

这些场景对应故障契约；本地供应商返回 2xx 不能证明真实供应商的后续业务结果。

## 本次验证结果

| 检查 | 结果 |
| --- | --- |
| ApiErrorHandlerTest | 5 项通过；数据库访问、事务建立、提交、回滚异常返回脱敏 503，普通应用错误不会被误报为数据库故障 |
| RetryPolicyTest | 9 项通过 |
| DeliveryTlsTest | 2 项通过；真实 TLS 临时错误后成功、未受信任证书拒绝 |
| NotificationApiIT | 9 项通过 |
| DeliveryIT | 10 项通过 |
| DeliveryDispatchIT | 2 项通过 |
| Maven 打包 | 成功生成可执行 JAR |
| 打包应用强制终止与重启 | 通过；供应商收到 2 次，最终 succeeded，第一次尝试记录为 unknown |
| 打包应用遇到数据库断开与恢复 | 独立脚本通过；断开后的提交与查询均返回 503 storage_unavailable，恢复后可提交，同键重试返回 200，原任务保留 |
| 数据库故障脚本被中断 | 分别发送 SIGTERM、SIGINT，返回 143、130；确认所启动的 Java、PostgreSQL 均退出，临时数据目录清理 |
| 外部 PostgreSQL 环境干扰 | 修复前两个脚本均在 createdb 阶段被无效 PGHOSTADDR 干扰；修复后注入无效 PGHOSTADDR 与 PGSERVICE，完整验证和数据库故障脚本均通过 |
| 待提交源码的独立副本 | 通过；不带 target、.local、.env 或 .git，重新构建并通过 37 项测试、应用崩溃恢复和数据库故障脚本 |
| 模拟供应商脚本 | /ok、/flaky、/permanent-fail、/retry-after 行为通过运行检查 |
| 独立代码复核 | 原边界问题修正后，定向复核未发现新的实质问题 |
| 新增验证脚本的独立复核 | 环境变量隔离、终止清理两项发现已修正，并完成定向静态复核；动态实验由实现方执行 |

共 37 个自动测试，0 失败、0 错误、0 跳过。完整脚本使用独立临时 PostgreSQL 数据目录，结束后停止数据库并清理目录。补充脚本的缺失 JAR 分支已检查，返回非零退出码并提示先构建。补充脚本的中断清理实验在 Java 与 PostgreSQL 均已启动时执行，不代表 SIGKILL 或机器掉电时仍能自动清理。

复核推动修正了临时 TLS 错误分类、旧式 HTTP 日期、JSON 转义后的入口体积边界，并补充并发重放、槽位释放及末次领取崩溃测试。另拒绝无法无损编码为 UTF-8 的 body，避免内容被静默替换。

随后补充事务异常映射：修复前新增回归测试中，事务建立、提交、回滚 3 个分支失败；修复后 5 项全部通过。错误处理测试在服务边界注入故障，检查真实 Controller 与异常处理器的 HTTP 响应；另使用打包应用及独立临时 PostgreSQL，实际停止并重启数据库验证断连与恢复。该实验现已整理为 `scripts/verify_storage_outage.py`，需独立执行，不属于 `scripts/verify.sh` 的固定步骤。尚未模拟真实数据库提交确认丢失，因此调用方仍需按文档使用相同 request_id 重试。

## 可复现性与限制

以 `git ls-files --cached --others --exclude-standard` 列出的正式提交文件为清单，复制工作区当前内容到临时目录；检查不包含原始 PDF、日历、过程草案、.env、.local、代码索引或已有 target。副本中重新构建并依次运行上述两个命令，均以 0 退出。验证使用本机已安装的工具链、Maven 缓存，以及通过 `-s` 显式传入的镜像配置；没有把该结果表述为全新机器或空依赖缓存安装成功。

本次正式副本验证包含 36 个文件，并注入无效的 PGHOSTADDR 和 PGSERVICE 以复验隔离。2026-10-14 的较早副本还包含 3 份过程草案，因此当时文件数为 39。本次复验后仅补充文档中的验证结果。正式文档的 18 处本地链接均可解析，`git diff --check` 通过。

## GitHub 托管 CI

2026-10-15，提交 `82c02f58a8386cc1d08f090486fe24ee0d02a70d` 的 [verify 工作流](https://github.com/cxzg007/rc_cxzg007/actions/runs/38704151052) 执行成功。环境为 GitHub Ubuntu runner、Temurin JDK 17 和 PostgreSQL 17 service container，实际执行 `bash scripts/verify.sh`。

云端日志确认：16 项单元测试、21 项集成测试全部通过，0 失败、0 错误、0 跳过；Maven 构建成功，打包应用输出 `packaged_app=passed`、`crash_recovery=passed`，观察到 2 次尝试和 2 次供应商接收。该工作流未执行独立的数据库断连脚本；数据库断连恢复依据上文的本地实验记录。

未在真实供应商或 Docker Compose 环境执行；CI 中的 PostgreSQL service container 不等同于验证本项目的 Compose 启动路径。生产负载、长期运行和数据库灾难恢复不在本次验证范围。
