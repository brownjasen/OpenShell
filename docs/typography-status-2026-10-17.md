# 字体清晰度与系统状态栏调整

## 原因与修改

终端和本地草稿此前使用逻辑 `Monospaced`，在当前 Windows 环境中的拉丁字形呈现为细衬线风格。启动代码还把文本抗锯齿强制设为 `on`，JediTerm 绘制又固定使用灰度模式；本机实际桌面设置为 LCD HRGB 与对比度 120。

- 终端与代码区优先使用 Consolas，缺少时依次寻找 Cascadia Mono、JetBrains Mono、Menlo、DejaVu Sans Mono，最后使用逻辑等宽字体。通过公开的 Swing `StyleContext` 获得带中文回退的复合字体，避免直接指定物理 Consolas 后出现中文缺字。
- 本地单行草稿使用界面字体，字号 13；多行草稿使用代码字体。保留已保存的终端字号与 A−/A+ 设置。
- 删除启动时强制的灰度抗锯齿属性。终端绘制读取系统 `awt.font.desktophints`，保留已有图形设置和 DPI 变换；无桌面设置时使用普通抗锯齿。不设置强制 DPI 比例或 Java2D 后端开关。
- 顶部连接状态与监控、布局、重连操作合成一个资源栏。CPU、内存、根分区显示百分比与细用量条，网络显示下行/上行速率。
- 宽窗口显示容量与首项负载，空间不足时收起辅助数值，完整数据放在悬停提示中。四项指标保留；窄窗口将操作折叠为“选项”，更窄时才换行。
- 断线或采集失败后显示“监控不可用”和横线，并清空用量条，避免把最后一次采样当作实时值。

## 验证与边界

- 新增字体与状态条回归测试：Windows 实际选择 Consolas、英文等宽及中文字符覆盖；继承桌面抗锯齿且不改变 DPI 变换和其他图形设置；360/700/1200 逻辑像素宽度下四项指标与用量条的布局，以及采集失败状态。
- 修复前字体选择和用量条测试失败，日志为工作区 `.work2/typography-status-baseline.log`。
- 最终普通 `mvn -B -ntp clean verify` 成功，**48 项测试**，失败、错误和跳过均为 0。终端输入测试还修正了未加锁读取缓冲区造成的测试竞态，等待条件使用完整屏幕文本，兼容行尾填充。
- 150% FlatLaf 缩放、非 headless 环境定向检查字体、会话、工作区与终端输入，**20 项通过**；窄窗口仍保留文件行。
- 以最终 JAR 为首项并排除 `target/classes`，使用隔离的回环 SSH/SFTP 连接生成普通、150% 和 200% 宽窗口的深浅主题预览，均输出 `WORKBENCH_APPEARANCE_PREVIEW_OK`。检查了终端字形、系统资源栏和窄窗口组件图片。
- 带 `sample` 的预览通过测试夹具输入示例监控数值，窗口标题明确写出“隔离预览 / 示例指标”；它们不是用户主机的真实采样。生产监控命令、采样间隔与数据计算没有改变。
- 图片来自创建了原生窗口句柄但未显示的 Swing 组件。**没有完成真实显示器的 ClearType、系统 DPI/多屏缩放与桌面逐项点击验收**；组件预览不能证明所有物理屏幕上的显示效果。

隔离 SSH 服务不支持 Linux 监控命令，预览日志中的 Unknown command 为预期。测试连接关闭警告与既有 Shade 资源重叠警告不计为失败。

## 产物与运行

- `target/openshell-1.0.0.jar`：6,988,789 字节。
- SHA-256：`06730a0f0cd45864a51c881a871a00f4306503b50239287f0812d12f136459f0`。
- 日志：`target/verification/typography-status-build.log`、`typography-status-scale.log`、`typography-preview.log`、`typography-preview-scale.log`、`typography-preview-2.log`。
- 预览：`target/verification/workbench-dark-1.5-sample.png`、`resource-header-1.5-sample.png`、`terminal-typography-1.5-sample.png`、`workbench-dark-2-1920-sample.png`；文件名中的 1920 为请求的窗口宽度，原生窗口管理可能调整实际尺寸。
- 修改前主要源码和 JAR 备份：工作区 `.work2/typography-status-before-20261017`。

停止旧 Java 进程，再重新编译并运行 `org.openshell.Main`，或启动上述 JAR。字体与桌面绘制设置需在新进程中生效；本轮没有新增依赖或更改用户配置格式。
