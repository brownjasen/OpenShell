# Windows x64 便携版发行与验收

日期：2026-10-17。版本 `v1.0.0`，发行包 `OpenShell-1.0.0-windows-x64-portable.zip`。使用方法为“全部解压 → 双击 OpenShell.exe”，无需安装程序或预装 Java。

## 组成与构建

- 原生 GUI 启动器由 JDK 17 `jpackage --type app-image` 生成，没有安装向导或注册表部署步骤；EXE manifest 为 `requestedExecutionLevel=asInvoker`。
- 程序使用当前可执行 fat JAR，SHA-256：`29abc49df7fd033958e3b0afc81dc44e424af7760d176785ba93f88fe9fe8b7e`。
- 内置 Eclipse Temurin JRE `17.0.20.1+1`，Windows x64；固定上游 ZIP 的 SHA-256 为 `bc21a93923103cdaac93ee337b0ae4365e739fde36df823dd456bc67c8a9d352`，与官方校验文件及 GitHub 资产 digest 一致。来源与源码标签在 `packaging/runtime-windows-x64.json`。
- 保留整个运行环境及 legal/NOTICE；程序第三方通知、原始 POM、JediTerm 对应源码与许可证在 `app/third-party/`。该目录不加入启动类路径；启动配置仅引用相对 `$APPDIR` 的应用 JAR。
- 中文说明、图标、版本/运行环境/JAR 摘要记录随包提供。配置继续位于 `%USERPROFILE%/.openshell`，不更改已有用户数据位置。

构建入口为 `build-portable.ps1`。它使用全新的中间目录，严格校验固定运行环境，拒绝覆盖已有 ZIP/SHA 文件，应用输入只从明确的 JAR/资料清单组装。`Create-AppIcon.ps1` 可以重新生成多尺寸 ICO。构建资料目录与实际启动输入分离，避免 jpackage 自动添加第三方资料 JAR 到类路径。

官方参考：[jpackage 17](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html)、[Temurin 固定发行来源](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.20.1%2B1)。

## 已验证

| 检查 | 结果 |
| --- | --- |
| 包内 Java 17 上的完整回归 | 141 项：137 通过、4 项 Windows 符号链接权限跳过，0 失败/错误；XML 的 java.home 指向实际解压运行环境 |
| 同一运行环境的包/重连/UI 定向验证 | 9/9 通过，含发布 JAR 的独立子 JVM Ed25519 与 Java 17 MR 选择检查 |
| 最终解压包的加密与类来源 probe | JSch Ed25519 签名、JDK 互验、篡改拒绝通过，运行类均来自包内 fat JAR，runtime_feature=17 |
| 实际原生 EXE | 在 Windows 11 x64 桌面显示完整首页；从含中文/空格的解压目录启动，移除 JAVA_HOME/JRE_HOME/JDK_HOME/CLASSPATH 等，PATH 仅保留 Windows System32 |
| 实际 JVM 来源 | 原生程序进程加载的 jvm.dll 位于其自身 runtime/bin/server/，未使用系统 Java |
| 内容清单 | 最终 ZIP 397 项，无个人 hosts.json、workspace.json、known_hosts、密钥或日志；包括 EXE、启动配置、运行环境、许可证、说明 |
| 运行环境摘要保护 | 故意传入错误 ZIP，执行前拒绝；未执行错误运行环境 |
| 输出保护 | 已有发行目录再次构建时拒绝覆盖 |

EXE 使用隔离配置验收，没有打开真实服务器连接或修改用户现有连接。桌面输入仅用于还原和关闭本轮测试窗口。最初受限桌面下进程已运行但无法被桌面工具枚举；在实际桌面重新启动后完成窗口观察，没有把“存在进程”当成 GUI 验收通过。

## 发布资产

- ZIP：51724152 字节，约 49.3 MiB。
- ZIP SHA-256：`21b314a9337818a66a909228c47a31bc99217541b995f5baafb4a587294d0a32`。
- 同版本公开 `SHA256SUMS.txt`；不把运行环境 ZIP、编译目录或个人配置加入 Git 历史。
- 源码与打包脚本随 main 提交；GitHub Release 的 `v1.0.0` 标签指向该发布提交。

## 边界

当前实际验证平台为 Windows 11 x64。Windows 10、Windows ARM64、32 位、物理休眠恢复及数小时耐久均不作为已通过结论。EXE 未代码签名，Windows 可能提示未知发布者；本版未创建自签名“受信任”证书或修改安全设置。产品现有凭据明文存储限制仍见 README。
