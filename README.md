# OpenShell

基于 Java Swing 的 SSH / SFTP 桌面工作台。覆盖终端交互、会话管理、远程文件、传输与资源监控。

## Windows 免安装版

到 [GitHub Releases](https://github.com/brownjasen/OpenShell/releases/latest) 下载 `OpenShell-1.0.0-windows-x64-portable.zip`，右键“全部解压”，然后双击其中的 **OpenShell.exe**。内置 Temurin Java 17，不需要安装 Java 或以管理员身份运行。

请保留完整的 `app` 和 `runtime` 文件夹，不要只复制 EXE，也不要在 ZIP 预览中直接运行。个人连接和设置继续使用 `%USERPROFILE%\.openshell`；发行包不包含个人配置。当前 EXE 未代码签名，Windows 可能提示未知发布者。

本轮实际验证环境为 Windows 11 x64；Windows 10、ARM64 和 32 位未作为已实测平台。校验值见同一 Release 的 `SHA256SUMS.txt`。

## 构建与运行

开发环境：JDK 17+、Maven。当前编译环境为 Windows 11 / JDK 21，以 `--release 17` 编译；便携版的 Temurin Java 17.0.20.1 运行环境已执行回归和原生 EXE 启动验收。

```powershell
git clone https://github.com/brownjasen/OpenShell.git
Set-Location OpenShell
.\build.ps1
java -jar target/openshell-1.0.0.jar
```

`build.ps1`、普通 Maven 和 IDEA 导入共用 `.mvn/maven.config`：项目内缓存为 `.build-cache/m2`，仓库设置为 `build-support/maven-settings.xml`，不修改全局配置。也可以直接在本目录运行：

```powershell
mvn clean verify
```

开发者在 Windows 上重建便携 ZIP：

```powershell
.\build-portable.ps1
```

需要包含 `jpackage.exe` 的 JDK 和 Maven；脚本验证应用、下载并校验固定版本的 Temurin JRE，产物在 `target/distributions/`。可用 `-JdkHome` 指定打包 JDK、`-RuntimeArchive` 指定已下载的固定 JRE ZIP、`-OutputDirectory` 指定新输出目录。`-SkipBuild` 仅适用于已有、已验证的当前可执行 JAR。脚本拒绝覆盖已有发行产物，不改系统 Java 配置。详见 [Windows 便携版验收记录](docs/windows-portable-2026-10-17.md)。

JediTerm 的固定版本二进制、POM 与对应源码已从 JetBrains 官方仓库取得、核对摘要，并放在 `build-support/maven-repository`。Maven 按普通依赖从该目录解析，首次导入不再要求 Python 预取或访问 JetBrains CDN；其他依赖由 Maven 下载。来源、摘要和许可证见该目录的 `ORIGIN.md`。

### IntelliJ IDEA

用本目录的 `pom.xml` 作为 Maven 项目打开。已有项目更新后，在右侧 Maven 工具窗口点击 **Reload All Maven Projects / 重新加载所有 Maven 项目**，完成同步后再执行 **Build → Rebuild Project**。运行入口是 `org.openshell.Main`。

如果之前出现 `com.jediterm.terminal` 程序包不存在，检查 Settings → Build, Execution, Deployment → Build Tools → Maven：启用 **Use settings from .mvn/maven.config**，不要用 Override 强制覆盖此项目的 User settings file 或 Local repository，再重新加载。无需手工添加 JAR、清空 IDEA 缓存或删除 `.idea`。

修复记录见 [IDEA 依赖解析修复](docs/idea-build-fix-2026-10-16.md)。

## 当前实现

- 真正的终端仿真：JediTerm + JSch，保留颜色、光标、备用屏幕和终端尺寸变化；连续解码 UTF-8，不按网络数据块单独解码。
- 多会话标签；明确的连接、重连、失败、断开状态；连接取消和旧回调隔离。重连保留单行/多行未发送草稿、选择与编辑设置，失败或取消后重试仍可恢复；草稿仅留在内存，不自动执行。
- 上方终端与命令输入，下方文件、传输、进程、端口、隧道；支持专注终端或专注文件布局。
- 单行/多行命令输入、按主机持久保存与搜索的输入框历史、Alt+/ 历史前缀补全和自定义常用命令。终端复制/粘贴默认使用 Ctrl+Shift+C / Ctrl+Shift+V；Ctrl+C 发送中断。
- 主机搜索、嵌套分组、新建/重命名/移动/删除空分组、主机与分组拖动移动；分组显示展开箭头与连接数量，最近记录默认收起并记住开合状态。刷新保留当前选中项，长名称和地址省略显示并可悬停查看。支持密码/私钥认证、终端编码配置、导入导出。
- 窗口、主题、主机侧栏、终端字体、会话分屏比例/布局、监控可见性，以及文件树宽度/表格列宽/隐藏文件选项保存到本机。
- 深浅主题统一字体和背景；界面使用微软雅黑 UI，终端与代码区在 Windows 优先使用 Consolas，并通过 Swing 复合字体保留中文回退。文本绘制遵循系统 ClearType 设置，不再强制灰度抗锯齿。
- 连接状态、CPU/内存/根分区用量条、网络速率与操作入口合成资源栏。宽窗口显示容量与负载细节，窄窗口收起细节并把操作移入“选项”，四项指标保留；完整数值可悬停查看。
- SFTP 目录树和文件表、数字大小排序、创建、重命名、删除与权限操作。目录枚举不改变操作目录；后台操作固定绝对路径。
- 文件上传/下载进入会话传输队列，独立 SFTP 通道，提供进度、速度、取消、从头重试和同名文件处理；先写临时文件再发布。传输中仍可浏览目录。支持 Ctrl/Shift 多选、文件夹递归上传下载、文件/文件夹拖入上传，保留结构与空目录；批次显示总进度，可整批取消或仅重试失败项。断点续传尚未实现。
- UTF-8 在线编辑、Ctrl+S 保存、修改标记、关闭时保存/放弃/取消。保存前校验远端内容版本；冲突可比较、重载、另存副本或明确覆盖，同目录临时文件完成后安全替换。加载失败时禁止编辑与保存；保存失败保留缓冲区与本地副本。重连后原编辑标签可继续保存，保留原版本校验，离线期间的远端修改仍触发冲突保护。
- Linux 资源监控：隐藏监控或切换会话时暂停采集，恢复时重置基准；资源栏 ⋮ 可选择网卡与挂载点，采集失败显示不可用并退避重试。进程和端口页提供表头数字排序、搜索、可见页自动刷新、开关与更新时间；端口可定位对应 PID。
- 目录书签按主机保存。“操作”菜单可选择跟随终端目录；需终端发出 OSC 7，提供仅当前 Bash 会话生效的集成命令草稿。Ctrl+Space /“补全”通过独立 SFTP 通道补全草稿中的远程路径。
- 本地/远程端口转发入口；SOCKS5 尚未实现，已移除其创建入口。

文件面板的“填入 cd”、历史与常用命令只生成输入草稿，不直接发送，也不覆盖已有草稿；确认终端处于 Shell 提示符后再发送。文件区支持 Ctrl/Shift 多选、Enter 打开、Delete 批量删除（目录仅删除空目录）、F5、Alt+Left/Right、F2 和 Ctrl+L，Ctrl+Shift+B 开关主机侧栏。

传输页没有任务时会说明使用方式，可直接点击“上传文件”，或点击“去文件页”选中远程文件后下载。上传目标同步文件页当前目录；目录尚未加载或断线时上传入口禁用。“清理记录”只移除任务记录，不删除文件。

### 终端输入与快捷键

上方终端直接操作远端 Shell；底部输入框用于本地编辑后发送。底部发送完成后保持原焦点，发送期间拦截重复提交；编辑历史命令后，新的内容作为当前草稿保留。断线后草稿仍可编辑、选中与复制。

| 操作位置 | 快捷键 | 行为 |
| --- | --- | --- |
| 终端 | Ctrl+C、Ctrl+L、Ctrl+F、Ctrl+A/E | 交给远端，分别用于中断、Shell 清屏、移动光标等 |
| 终端 | Tab、↑/↓、Ctrl+↑/↓ | 交给远端 Shell 或全屏程序 |
| 终端 | Ctrl+Shift+C 或 Ctrl+Insert | 复制选中的纯文本 |
| 终端 | Ctrl+Shift+V 或 Shift+Insert | 粘贴；保留中文，统一 Windows 换行，遵循远端括号粘贴模式 |
| 终端 | Ctrl+Shift+F / Ctrl+Shift+A | 本地查找 / 全选输出 |
| 终端 | Shift+PageUp / PageDown | 翻阅本地输出 |
| 终端 | Ctrl+Shift+L | 清除本地回滚记录（保留提示行） |
| 终端与本地草稿 | F6 | 在两处切换焦点 |
| 本地草稿 | Esc | 回终端，保留草稿 |
| 本地草稿 | Ctrl+C / Ctrl+V | 普通文本复制 / 粘贴 |
| 单行草稿 | Enter、↑/↓、Alt+/ | 发送、浏览本机历史、历史前缀补全 |
| 单行草稿 | Ctrl+Space | 远程路径补全；只修改草稿，不发送 |
| 多行草稿 | Ctrl+Enter、Shift+Enter | 发送全部、插入换行；可切换为 Enter 发送 |

单行粘贴不额外补回车；复制的内容如果本身包含换行，仍按原内容及远端当前模式处理。工具栏的“快捷键”和按钮悬停提示可随时查看这些用法。

这些条目表示代码能力。真实 Linux 全屏程序、不同发行版及高级转发等仍需按路线图进一步验收。

## 配置与数据

默认目录为 `%USERPROFILE%\.openshell`，包含 `hosts.json`、`known_hosts` 和 `workspace.json`。首次连接需确认服务器指纹，后续复用；已保存的主机密钥变化时拒绝静默接受。

`hosts.json` 当前仍包含用户选择保存的凭据，系统凭据库迁移属于后续阶段。主机配置与导出采用临时文件替换，失败会提示；无法解析的已有配置不会被新列表覆盖。

`workspace.json` 独立保存工作区设置、自定义命令及命令输入框历史；每台主机最多保留 1000 条历史。记录默认为开启，可在“历史”窗口关闭或清空当前主机历史；直接在终端输入的内容不会由此功能记录。历史及自定义命令以明文保存在本机。损坏的工作区文件会保留并提示，不会静默覆盖。

隔离运行或测试时可指定单独目录：

```powershell
java -Dopenshell.configDir=./target/preview-config -jar target/openshell-1.0.0.jar
```

## 验证与后续工作

默认测试使用本机回环地址和临时密钥，不连接用户保存的服务器。覆盖连接竞态、指纹校验、终端解码与渲染、PTY 更新、SFTP 路径、编辑保存和配置失败保护。真实服务器验收需显式开启，并限于用户已授权的目标与独立临时目录。组件预览生成在 `target/verification/`。

真实 Linux 验收会在目标服务器创建独立的 `/tmp/openshell-acceptance-<UUID>` 目录并在测试后清理。只用于明确授权的测试机；先在应用中保存连接并核验服务器指纹，然后同时指定连接名称、预期地址与已有 `known_hosts` 文件，缺少这些条件会拒绝执行：

```powershell
mvn -B -ntp '-Dtest=LiveLinuxAcceptanceIT' `
  '-Dopenshell.acceptance.live=true' `
  '-Dopenshell.acceptance.hostName=YOUR_TEST_CONNECTION' `
  '-Dopenshell.acceptance.expectedHost=test.example.com' `
  "-Dopenshell.acceptance.approvedKnownHosts=$env:USERPROFILE/.openshell/known_hosts" test
```

仓库不包含个人连接记录、凭据、私钥、指纹库或本机测试日志。固定 JediTerm 依赖的原始来源、对应源码与许可证位于 `build-support/maven-repository/`。

- [制作与优化路线](docs/openshell-roadmap-2026-10-16.md)
- [第一批实施与验证记录](docs/m1-implementation-2026-10-16.md)
- [第二批实施与验证记录](docs/m2-implementation-2026-10-16.md)
- [字体与工作台外观修复](docs/ui-repair-2026-10-16.md)
- [终端输入与快捷键修复](docs/terminal-usability-2026-10-17.md)
- [字体清晰度与系统状态栏调整](docs/typography-status-2026-10-17.md)
- [进程占用、数字排序与自动刷新修复](docs/process-monitor-fix-2026-10-17.md)
- [传输队列、独立通道与覆盖保护](docs/transfer-implementation-2026-10-17.md)
- [传输页入口与空白状态引导](docs/transfer-guidance-2026-10-17.md)
- [六项功能实施与使用说明](docs/six-features-implementation-2026-10-17.md)
- [修复前的真实服务器与压力验收：3 项缺陷记录](docs/acceptance-2026-10-17.md)
- [重连草稿、编辑恢复与发布包修复验收](docs/reconnect-package-fix-2026-10-17.md)

后续仍包括断点续传、更多 Shell/发行版兼容验收、跳板机、系统凭据库、SOCKS5 和安装向导。当前可构建可执行 JAR 与 Windows x64 免安装便携 ZIP。
