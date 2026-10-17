# OpenShell 日常流程与真实服务器验收

> 以下为修复前的历史验收结果。三项缺陷已在后续授权修改中修复，当前结果见 [重连状态与发布包修复验收](reconnect-package-fix-2026-10-17.md)；原失败记录保留。

日期：2026-10-17。结论：**验收未通过，确认 3 项产品缺陷。** 本轮授权为测试，只新增验收用例和报告，正式产品代码与 JAR 未修改。失败断言保留，没有修改成只检查当前错误行为。

## 结果概览

本轮新增显式验收用例 13 项：10 通过、3 失败、0 错误、0 跳过。服务器没有 tmux，相关实测没有执行；没有将它计为已通过。此前 132 项普通回归的历史证据为 128 通过、4 项 Windows 符号链接权限跳过，不能替代本次验收。

| 场景 | 测试数 | 最终结果 | 核实内容 |
| --- | --- | --- | --- |
| 日常流程，隔离回环 | 1 | 通过 | 连接、中文命令字节、文件页打开编辑、Ctrl+S、目录上传下载、远端断线、重连、关闭 |
| 大文件与断网，真实 TCP/SSH/SFTP | 3 | 通过 | 256 MiB 上传下载 SHA-256；延迟代理中途 RST 后保护原目标；新连接重新入队成功 |
| 10000 文件目录，隔离回环 | 1 | 通过，加载耗时需继续分解 | 实际目录枚举、数值排序、过滤、多选与 EDT 心跳 |
| 8 会话短时压力，隔离回环 | 1 | 通过 | 持续 70.141 秒，3 次整批断线重连，2888 帧 ANSI/中文，112 次命令字节核对 |
| 重连后的编辑状态 | 3 | 1 通过、2 失败 | 仅断线保护通过；重连丢草稿、旧编辑器无法恢复保存 |
| 已授权测试机 真实 Linux | 3 | 通过 | Bash、Vim、top、PTY 尺寸、OSC 7、8 MiB 往返、内容冲突、进程/端口/监控 |
| 独立发布 JAR | 1 | 失败 | 独立子 JVM 实测 Ed25519 初始化失败，测试依赖不参与补救 |

## 必须修复的问题

### P1：独立 JAR 的 Ed25519 实现失效

- 触发：使用现有 `target/openshell-1.0.0.jar` 运行，主机指纹库只信任该服务器的 Ed25519 公钥。
- 现象：直接应用连接报 `HostKey has been changed`；独立子 JVM 的 Ed25519 初始化用例失败。IDE/Maven 类路径中的原始 JSch 依赖可以正常使用 Ed25519。
- 根因证据：原始 JSch JAR Manifest 有 `Multi-Release: true`；打包制品保留 `META-INF/versions/15/...SignatureEd25519.class`，但 Manifest 没有 Multi-Release 标记。正式 Shade 配置在 [pom.xml](../pom.xml)。
- 对照：仅对测试副本补上 Manifest 标记，子 JVM 输出 `ED25519_OK`，并能用原有 OpenShell 指纹库通过 已授权测试机 严格校验；实际 Ed25519 指纹与原记录一致。没有关闭校验，没有接受新的未知密钥，也没有修改用户信任库。
- 用例：[PackagedJarAcceptanceIT](../src/test/java/org/openshell/ssh/PackagedJarAcceptanceIT.java)。

### P2：重连丢失未发送命令草稿

- 复现：单行框、多行框分别输入内容，不发送；服务器关闭当前测试连接；点击重连。
- 现象：单纯断线时两个缓冲区仍在；成功重连后两个缓冲区都为空。没有自动发送或写入已执行历史。
- 根因：[SessionPanel.reconnect](../src/main/java/org/openshell/ui/SessionPanel.java) 直接清理旧 TerminalPanel，再新建空输入框；没有保存、恢复草稿。
- 修复应满足：两种草稿及编辑模式恢复；不自动发送、不覆盖更新的输入、不混到其他主机。

### P2：重连后旧编辑器成为无法保存的孤立标签

- 复现：从文件页打开远端文件并修改；连接断开，保留编辑标签；成功重连后在原标签 Ctrl+S。
- 现象：内容仍在，但保存失败，远端仍是旧内容；`ownsClient(oldEditorClient)` 为 false，影响 MainWindow 按会话寻找关联编辑标签的关闭保护。
- 根因：[RemoteEditorPanel](../src/main/java/org/openshell/ui/RemoteEditorPanel.java) 固定持有旧 SFTP 客户端；重连清理永久关闭该客户端，且 [ownsClient](../src/main/java/org/openshell/ui/SessionPanel.java) 只匹配新传输。
- 修复应满足：通过稳定会话身份重新关联客户端，保留本地修改及原远端版本，重连后保存仍检测远端冲突，关联关闭仍保护脏标签。
- 两项重连问题均在 [ReconnectDraftAcceptanceIT](../src/test/java/org/openshell/ui/ReconnectDraftAcceptanceIT.java) 保留失败复现。

## 测量及边界

- 回环 256 MiB：上传 2.378 秒、约 107.64 MiB/s；下载 2.056 秒、约 124.52 MiB/s，两端 SHA-256 相等。只代表本机测试环境。
- 延迟代理实际断开加密 TCP 流。上传约 1011499 字节、下载约 720610 字节后失败，已有目标均不变。断线上传留下 1 个远端 `.part`，现有错误消息明确告知；隔离测试临时目录最终清理。不是断点续传证明。
- 万文件：创建测试数据 12.468 秒，首次枚举与表格填充合计 56.622 秒。该耗时同时包含 MINA/Windows 文件系统、协议与客户端，不能直接判定为真实 Linux 或 UI 性能。最大 EDT 排队 567 毫秒；排序/过滤/多选约 20–38 毫秒，恢复全部行约 286 毫秒。
- 8 会话压力：三轮重连共建 32 个连接；关闭后服务器与客户端连接均为 0。OpenShell 线程从基线 1 增至峰值 44，最后回到 1（共享监控截止线程）；EDT 最大排队 144 毫秒。该 70 秒短测不能证明数小时/数天无泄漏；隐藏页的监控与自动刷新保持暂停。
- 真实 已授权测试机：Linux `6.12.111+deb13-cloud-amd64`；Vim 全屏中文写入并保存退出、top 显示与退出、PTY `100×28`、Bash OSC 7 目录事件通过。8 MiB 上传下载及安全编辑冲突检查共 47.448 秒，文件 SHA-256 一致；下载采样速率约 0.21–0.30 MiB/s，未与其他客户端作同链路对照，不能直接归因产品。
- 真实进程采样、监听端口、资源枚举、监控暂停恢复与网卡选择通过。不执行终止进程、修改服务、安装软件等操作；不记录进程命令行或凭据。
- 主机连接复用用户授权的 已授权测试机 保存记录（该记录当前为密码认证），认证信息只在内存中使用。测试配置独立，信任记录来自用户已有且对应目标的 OpenSSH 记录。实际写入只在唯一的 `/tmp/openshell-acceptance-<UUID>`，创建的文件及目录已清理，未改业务数据、启动文件、实际连接配置或信任库。
- 全屏程序输入经应用连接与 Swing/终端模型执行；并非原生键鼠和输入法驱动实测。物理休眠、长时间断网、系统剪贴板、拖放驱动、原生 DPI/字体观感和数小时耐久仍未验收。tmux 未安装，未擅自安装。

## 测试脚本自身的初次误判

- soak 直接匹配 JediTerm 原始中文文本时忽略了宽字符延续格 U+E000。原始缓冲区证明字已渲染；按已有测试去掉延续格后，颜色断言保持严格，完整 70 秒重跑通过。
- top 收到 q 后立即发送下一命令会被恢复终端时的输入清理丢弃。等提示符返回后再发送，真实 top 测试通过。
- 实际网络下载仍有进度时，最初每方向 30 秒的测试期限不足。改为带进度输出的有界测试后实际完成；没有把该超时报告为产品故障。初次日志保留，最终结果使用纠正后的记录。

## 复现与日志

普通 Maven 默认不收集 `*AcceptanceIT`。隔离测试显式运行，不需要实际主机凭据：

```powershell
mvn -B -ntp '-Dtest=DailyWorkflowAcceptanceIT,NetworkTransferAcceptanceIT,DirectoryScaleAcceptanceIT,SessionSoakAcceptanceIT,ReconnectDraftAcceptanceIT,PackagedJarAcceptanceIT' test
```

其中两项重连与一项打包用例应保持失败直至正式修复。包用例使用当前真实制品，不让测试依赖 JAR 掩盖打包问题。真实主机测试另外要求 `openshell.acceptance.live=true` 与已经核验的 known_hosts 隔离副本路径，不会默认联网；仅应在再次得到目标授权时重跑。

关键记录位于 `../.work2/`：

- `acceptance-workflow-network.log`：日常流程、256 MiB 与实际 TCP 中断。
- `acceptance-scale-soak.log`：万文件测量与初次 soak 误判；最终 soak 结果见下一项。
- `acceptance-live-soak-final.log`：真实 Linux 3 项通过，重连 2 项失败，最终 soak 通过。
- `acceptance-packaged-jar.log`：独立子 JVM 的正式 JAR 失败。
- `live-azure-probe.log`、`live-azure-manifest-only-probe.log`：原指纹库下正式 JAR 失败与只修测试副本 Manifest 后成功的对照。
- 结构化计数来自 `OpenShell/target/surefire-reports/TEST-*AcceptanceIT.xml`，已留档到 `../.work2/acceptance-evidence-20261017/`（包含 summary.json）；压力指标在 `OpenShell/target/verification/acceptance-*.txt`。

测试对象制品保持不变：7153051 字节，SHA-256 `039731C8ECAA00F4A03FD4AB833875A2CF85724C5F3076E003E4631C4E3C0FC6`。下一步应优先修复这 3 项，再用保留的失败用例转绿；这次测试不能作为成熟主力工具的验收通过证明。
