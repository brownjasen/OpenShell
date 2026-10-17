# 终端输入、复制粘贴与快捷键修复

本轮按用户指定的“终端输入、复制粘贴和快捷键”实施。

## 已修复的操作问题

- Ctrl+L、Ctrl+F 原先被 JediTerm 默认的本地清屏和查找截走。现交给远端；本地查找为 Ctrl+Shift+F，清回滚为 Ctrl+Shift+L。Ctrl+A/E、Ctrl+C、Ctrl+V、Tab 和方向键保留远端语义，Ctrl+↑/↓ 不再被本地滚动截走。
- 终端复制支持 Ctrl+Shift+C / Ctrl+Insert，粘贴支持 Ctrl+Shift+V / Shift+Insert。工具栏与右键菜单使用相同动作，菜单使用中文。未选中时复制会提示；剪贴板占用或无文本时给出反馈。
- 复制使用渲染后的纯文本，保留中文，不携带 ANSI 转义码。修正固定 JediTerm 版本全选终点多出一行的问题，并省略尚未使用的屏幕空行。
- 粘贴先统一 CRLF、CR、LF，再交给终端处理，避免 Windows CRLF 变成两次回车。不额外补回车；远端启用括号粘贴模式时仍保留相应起止序列。剪贴板原有换行仍按远端当前模式处理。
- 底部草稿发送完成后不再主动切换焦点。F6 在终端与草稿间切换，草稿内 Esc 回终端并保留内容；重新显示会话时尝试恢复上次输入位置，避免覆盖当前会话内已有焦点。
- 发送期间禁止再次提交，避免连续 Enter 重复执行。发送成功仅清理未被继续编辑的原稿；断线时草稿仍能编辑、选中与复制，发送与粘贴到远端被拦截。
- 修改召回的历史命令后重置历史导航状态，↓ 不再把修改覆盖为旧草稿。多行草稿统一换行，并在成功后按设置清空。
- 工具栏增加快捷键说明与按钮悬停提示，区分远端终端与本地命令草稿的操作位置。

## 验证

- 先复现 Ctrl+L/F 被截走、重复 Enter 双发、编辑历史后恢复旧稿，再实施修复。修复前的失败日志保存在工作区 `.work2/terminal-input-baseline.log` 与 `terminal-shortcut-baseline.log`。
- 普通 Maven `clean verify` 成功；最后一次源码更新后 `mvn -B -ntp verify` 成功，共 **45 项测试**，失败、错误和跳过均为 0。
- 新增 12 项终端集成测试与 2 项剪贴板异常测试。使用隔离的内存剪贴板、临时配置和本机真实 SSH 协议服务，检查远端实际收到的字节。覆盖控制键、中文复制粘贴、IME 合成与提交事件、CRLF、括号粘贴模式开关、备用屏幕应用方向键、工具栏按钮、断线草稿、重复提交及多行发送。
- 使用最终 JAR 置于类路径首位并移除 `target/classes`，创建完整主窗口与本机 SSH/SFTP 会话；普通和 150% FlatLaf 缩放均输出 `WORKBENCH_APPEARANCE_PREVIEW_OK`。检查深色整窗图片，终端工具栏按钮均可见。
- 以上输入事件由 Swing 组件测试驱动，预览来自未显示的真实 Swing 组件。**未完成系统剪贴板、真实 Windows 输入法候选窗、桌面鼠标拖选、原生焦点切换或真实 Linux vim/top/less 的人工验收。** 没有读取或改写用户系统剪贴板，也没有连接用户保存的服务器。

隔离服务不提供 Linux 监控命令，预览中的“监控不可用”和日志中的 Unknown command 属于预期。关闭测试连接时的连接重置警告，以及既有 Shade 资源重叠警告不计为测试失败。

## 产物

- `target/openshell-1.0.0.jar`：6,981,370 字节。
- SHA-256：`7b86317e5e297a914b599bedce0781334275caab4dc120a4466970b98c06c97b`。
- 日志：`target/verification/terminal-usability-build.log`、`terminal-preview.log`、`terminal-preview-scale.log`。
- 预览：`target/verification/workbench-dark-1.png`、`workbench-dark-1.5.png`，同目录还有浅色与隧道预览。
- 修改前备份：工作区 `.work2/terminal-usability-before-20261017`；本轮差异：`.work2/terminal-usability.diff`。

IDEA 中停止旧进程后，重新编译并运行 `org.openshell.Main`；也可以运行新的可执行 JAR。没有新增依赖或改变配置文件格式。
