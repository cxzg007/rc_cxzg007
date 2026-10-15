# 验证记录

日期：2026-10-15。环境：macOS arm64、Temurin JDK 17.0.19、Maven 3.9.16、PostgreSQL 17.11、Spring Boot 4.1.1。

本轮针对 code review 的 7 项发现补充失败回归并修正；独立复核还补充了“调用前已中断，不应启动 HTTP 传输”的修复。验证范围是本地 PostgreSQL、HTTP/TLS 和打包应用，不代表真实供应商、生产负载或任意故障下的时限保证。

## 复现路径

准备 JDK 17、Maven、Python 3 和原生 PostgreSQL 17，在仓库根目录运行：

```bash
bash scripts/verify.sh
python3 scripts/verify_storage_outage.py
```

第一条命令在未设置 TEST_DB_URL 时创建临时数据库，执行单元/集成测试，并启动打包应用验证强制终止后的恢复。若显式设置 TEST_DB_URL，必须使用可清空的测试数据库。第二条始终创建自己的临时数据库，实际停止/重启它，不操作 TEST_DB_URL 指向的数据库。两条命令正常结束后均清理自己启动的进程和数据库。

本机 Maven Central 直连存在 DNS 超时，实际构建使用 `bash scripts/verify.sh -s .local/maven-settings.xml`。镜像配置不含凭据，也不属于仓库内容；其他环境按需透传自己的 Maven 参数。仅运行 `mvn test` 不包含数据库集成测试和进程实验。

## 修复前后的对照

| 发现 | 修复前证据 | 修复后检查 |
| --- | --- | --- |
| 已获得数据库连接后没有操作预算 | 真实表锁、多阶段读取、提交阻塞 3 项新增回归均在调用方等候期限内未返回 | 表锁返回脱敏 503；任务/历史读取共享截止时间；提交阻塞返回 503，连接池活动借用数回到 0 |
| JDK 内部重试重新计时 | 1 秒预算内，第一次连接延迟 600ms 后断开，第二次再延迟 600ms 返回 204；原实现仍报成功 | 返回 http_timeout；真实 socket 检查超时/中断后的连接关闭 |
| 非法 Unicode URL 被数据库替换 | 高、低孤立代理字符经 API 接受后被替换成 `?` | 入口返回 400，不写入；中文、emoji、普通 query 和编码字符保留 |
| 领取过程跨过任务年龄期限 | 真实领取完成后推进测试时钟，原 worker 仍联系供应商 | 无供应商请求，任务和本次历史原子记录为 age_budget_exhausted |
| 明确 TLS 拒绝被重试 | 6 种 fatal alert 的新增测试均错误返回 retryable | illegal_parameter、access_denied、decode_error、decrypt_error、missing_extension、unsupported_extension 均终止；临时 TLS 故障仍可重试 |
| 空 query 分隔符被丢掉 | `/hook?` 实际发送成 `/hook` | 按提交者选择返回 400 empty_query_not_supported |
| 闰秒 Retry-After 被忽略 | 23:59:60 被当成无效日期，本地退避早于供应商期限 | 三种 HTTP 日期形式均等待至下一分钟 |
| 已中断线程仍启动异步 HTTP | 新增边界测试确认预中断时仍调用 sendAsync | 不启动传输，保留中断标志；受控 future 另验证取消与响应完成竞争时关闭 body |

修复过程也发现异常映射测试需要适配 Controller 新增的 Deadline 参数，已修正测试请求属性和服务桩，并增加预算耗尽异常的 503 检查。

## 自动测试与故障实验

| 检查 | 数量与覆盖 |
| --- | --- |
| ApiErrorHandlerTest | 6 项；访问、预算、事务建立、提交/回滚异常的脱敏响应，以及普通程序错误不误报为数据库故障 |
| RetryPolicyTest | 10 项；错误分类、次数/年龄预算、退避、三种日期与闰秒 |
| DeliveryTlsTest | 8 项；真实 TLS 临时错误恢复、未受信任证书、明确协议/访问拒绝 |
| OriginPolicyTest | 3 项；Unicode、空 query、合法 URL 保留 |
| DeliveryDeadlineTest | 6 项；内部重试总时限、响应体关闭、超时/中断取消、预中断、响应完成竞争 |
| NotificationApiIT | 14 项；鉴权、并发幂等、调用方隔离、重放、请求边界、表锁、多次读取共预算、提交阻塞 |
| DeliveryIT | 12 项；准确转发、重试、领取/年龄边界、租约/token、原子写回及写回锁超时恢复 |
| DeliveryDispatchIT | 3 项；并发槽位、领取异常/锁超时后的槽位释放 |
| DatabaseBudgetIT | 4 项；多 SQL 累计预算、上游耗时、池耗尽、池中所有连接的读取/SQL 超时设置恢复 |

汇总为 **66 项：33 项单元测试、33 项集成测试，0 失败、0 错误、0 跳过**。提交前最终结果由下方独立副本验证确认。

`taskReadAndHistoryReadShareOneDeadline` 会在第二次借用余量不足时提前拒绝，因此证明的是同一截止时间及保守准入；`DatabaseBudgetIT.sequentialSqlStatementsShareTheRemainingTime` 才是两条真实 SQL 的累计预算检查。两者没有混为一项保证。

提交阻塞使用延迟执行的 PostgreSQL 约束触发器，验证 COMMIT 等待有界、池借用释放和同键重试。**未模拟“数据库已经提交成功、仅提交确认丢失”的网络故障**，因此不声称证明了该故障窗口的所有行为；API 仍将其视为不确定结果。

打包应用实验：`scripts/smoke.py` 在供应商收到请求后强制终止应用，再启动恢复，检查第一次尝试为 unknown、最终 succeeded，以及可能重复接收。`scripts/verify_storage_outage.py` 实际停止/重启独立 PostgreSQL，检查断连后 POST/GET 返回脱敏 503、原任务保留、恢复后同键重试返回 200。

## 本地耗时参考

本轮使用打包应用、临时 PostgreSQL 和本地供应商，worker 开启，单个调用方顺序发送；每种请求先预热 5 次，再记录 30 次。时间包含客户端 JSON 序列化和 HTTP 往返。结果单位为毫秒：

| Body | 修复前 p50 / p95 | 修复后 p50 / p95 |
| --- | --- | --- |
| 1 KiB | 2.254 / 3.415 | 2.628 / 4.317 |
| 1 MiB | 13.876 / 19.239 | 14.461 / 19.997 |

这是本地小样本参考，不是压测、性能因果对照或生产百分位校准。不能据此证明 5 秒预算与 100/200ms 预留最优；代表性负载下的端到端分布、池争用和各阶段耗时仍需后续观察。当前未新增监控平台。

## 最终独立副本验证

从 `git ls-files --cached --others --exclude-standard` 列出的 41 个正式文件复制工作区内容到临时目录，不带 target、.local、.env、.git、原始 PDF、日历或过程草案。使用本机工具链、Maven 缓存和显式传入的镜像配置，重新构建并执行上述两条命令；另注入无效 PGHOSTADDR 和 PGSERVICE 检查脚本环境隔离。

两条命令均以 0 退出：66 项测试通过、Maven 构建成功；打包应用输出 `packaged_app=passed`、`crash_recovery=passed`，观察到 2 次尝试和 2 次供应商接收。数据库故障实验输出 `storage_outage=passed`、`offline_post=503`、`offline_get=503`、`recovery=passed`、`same_key_retry=200`、`accepted_task_retained=true`。

已比对副本与工作区，源码、测试、配置和脚本内容一致；此后仅补充文档结果。正式文档 16 处本地链接可解析，`git diff --check` 通过。独立只读复核确认本轮修正已落实，没有遗留修正项；动态测试由实现方执行。没有把本机缓存环境表述成全新机器或空缓存安装成功。

## 托管 CI 与验证限制

仓库的 `.github/workflows/ci.yml` 使用 GitHub Ubuntu runner、Temurin JDK 17 和 PostgreSQL 17 service，执行 `bash scripts/verify.sh`；独立数据库断连脚本需要另行执行。

2026-10-15，本轮修复提交 `b55407a879122bf5abe0d8145101cd52ef45e1c1` 的 [verify 工作流](https://github.com/cxzg007/rc_cxzg007/actions/runs/38715829972) 执行成功。云端日志确认 33 项单元测试、33 项集成测试全部通过，Maven 构建成功，打包应用输出 `packaged_app=passed`、`crash_recovery=passed`，观察到 2 次尝试与 2 次供应商接收。本段在结果确认后补记，仅涉及文档。

早期提交 `82c02f58a8386cc1d08f090486fe24ee0d02a70d` 的托管验证曾通过当时的 37 项测试及应用崩溃恢复；该历史结果不代替本轮验证。独立数据库断连脚本的结果来自上文的本地实验，未表述为已在 CI 中执行。

2026-10-14 已检查故障脚本的 SIGINT/SIGTERM 清理、缺失 JAR 分支和模拟供应商端点；这些历史检查未冒充本轮重新执行。SIGKILL 或机器掉电下的脚本清理不在保证范围。

未在真实供应商或 Docker Compose 启动路径执行；CI 的 PostgreSQL service container 不等同于 Compose 启动验证。生产负载、长期运行、数据库灾难恢复，以及任意故障下严格的 HTTP 响应期限均不在本轮验证范围。
