OpenShell 1.0.0 — Windows x64 免安装便携版

使用方法
1. 右键 ZIP → 全部解压，将整个 OpenShell 文件夹放到你选择的位置。
2. 双击 OpenShell.exe 即可启动。不需要安装 Java，不需要管理员权限。
3. 请保留 app 和 runtime 文件夹；不要只复制 EXE，也不要在 ZIP 预览中直接运行。

系统与数据
- 面向 Windows 10/11 64 位（x64）；本轮实际启动验证环境为 Windows 11。
- 内置固定版本的 Eclipse Temurin Java 17，只供本程序使用，不改系统 Java 或 PATH。
- 连接与设置仍存放在 %USERPROFILE%\.openshell，沿用 JAR 版的位置。
- 发行包不含任何个人连接、密码、私钥或主机指纹库。
- 当前选择保存的凭据会以明文保存在本机 hosts.json，请妥善保护该目录及导出文件。
- 升级可关闭旧程序后将新版解压到新目录；现有个人配置不随程序目录删除。

发行信息
- 源码与问题反馈：https://github.com/brownjasen/OpenShell
- 下载：https://github.com/brownjasen/OpenShell/releases
- 内置运行环境与第三方许可证见 runtime/legal、app/third-party。
- 本 EXE 未购买或使用代码签名证书；Windows 可能显示未知发布者提示。
- Windows ARM64、32 位、休眠恢复和长时间耐久尚未作为本版验收结论。
