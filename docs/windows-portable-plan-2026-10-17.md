# Windows 免安装发行计划

目标：发布 `v1.0.0` 的 Windows x64 ZIP，解压后双击 `OpenShell.exe`，无需安装程序、管理员权限或预装 Java。使用 JDK `jpackage --type app-image`，保留程序目录及内置运行环境。

1. 固定 Eclipse Temurin JRE 17 Windows x64 的下载版本、来源和 SHA-256；原运行环境的 legal/notice 文件全部保留。
2. 添加可重复执行的 PowerShell 打包脚本，只从明确列出的 JAR、说明与第三方资料组装输入，拒绝覆盖既有输出；不打包用户配置、日志或测试结果。
3. 生成 EXE 图标、中文使用说明及 ZIP/SHA-256；沿用 `%USERPROFILE%/.openshell` 配置位置，不自动迁移或导出个人数据。
4. 在包内 Java 17 上运行回归、Ed25519 和打包验证；从带空格/中文的解压目录启动原生 EXE，临时移除 Java 环境变量与 PATH 条目，用隔离配置确认窗口及实际 JVM 路径。
5. 审查打包清单和暂存边界，提交打包脚本与文档，推送 main；创建指向该提交的 GitHub `v1.0.0` Release，上传 ZIP 和 SHA-256。
6. 下载 GitHub Release 资产核对摘要，记录验证与使用入口。未验证的 Windows 版本、ARM64 和签名状态如实说明。

来源：Oracle JDK 17 jpackage 文档；Eclipse Adoptium 官方 GitHub 发行资产。构建工具可以使用本机 JDK；用户运行只使用包内固定的 Temurin JRE。
