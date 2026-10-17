# IDEA 构建依赖修复

日期：2026-10-16

## 问题与原因

用户在 IDEA 编译 TerminalPanel 时出现 `com.jediterm.terminal` 程序包不存在，随后产生大量关联错误。

前两批只验证了使用单独 Maven 设置和 `.work2/m2` 缓存的构建脚本，没有验证用户默认 IDEA 导入流程。IDEA 日志确认 JediTerm 从官方仓库下载时，其 CDN 域名解析到回环地址，连接被拒绝；默认 Maven 缓存只有对应的 `.lastUpdated` 失败记录，没有 JAR。

脚本可以构建不等于 IDE 已经解析依赖。此前交付没有补齐两者的配置，是此次问题的直接遗漏。

## 修复

- 增加 `.mvn/maven.config`，普通 Maven 默认使用项目的仓库设置及 `.build-cache/m2` 缓存。IDEA 需要启用 `Use settings from .mvn/maven.config`，且不使用 Override 覆盖相应选项。
- 在 `pom.xml` 增加 `openshell-bundled` 文件仓库。固定版本 JediTerm 3.76 的两个二进制 JAR、两个 POM 和两个对应源码 JAR放在 `build-support/maven-repository`，均从 JetBrains 官方仓库获取并核对发布方 SHA-1；同时记录 SHA-256，包含来源及许可证说明。
- 继续使用普通 Maven 依赖及传递依赖解析，没有改成 system scope，也没有手工修改 IDEA 类路径。
- `build.ps1` 进入项目目录，复用同一份 `.mvn` 配置；移除必经的 Python/官方 CDN 预取步骤。显式 `LocalRepository` 参数仍可覆盖脚本缓存位置。
- 不修改终端和其他业务功能，不更改系统 DNS、hosts 或用户全局 Maven 设置；不删除 `.idea` 或用户保存的连接数据。

## 验证结果

1. 修复前：用户截图和本机 IDEA 日志确认 JediTerm 解析失败；普通默认仓库的离线编译也因依赖不完整失败。
2. 修复后，在尚不存在的项目缓存下，从 OpenShell 目录直接执行 `mvn -B -ntp -U clean verify`，未经过 build.ps1，也未先向该缓存复制依赖：构建成功，27 项测试全部通过，失败/错误/跳过均为 0。
3. 新缓存的 `_remote.repositories` 确认 JediTerm core/UI 的 JAR 和 POM 来自 `openshell-bundled`。
4. 从工作区根目录执行 `./OpenShell/build.ps1 -Goals @('-o','compile')` 成功，确认脚本正确定位项目配置，已取得依赖后可以离线编译。
5. 使用最终可执行 JAR 构造终端及非 headless 主窗口并释放，输出 `PACKAGED_APP_SMOKE_OK`。
6. `SHA256SUMS` 中六个文件校验通过。

当前 JAR：`target/openshell-1.0.0.jar`，6,971,183 字节。

SHA-256：`071c53332d4ab6aecdda60d29ba769650ab0d75c73e7f74d0ab485152289a74e`。

日志保存于 `target/verification/idea-default-build.log`、`idea-script-build.log`、`idea-smoke.log`。修复前后构建配置差异位于工作区 `.work2/ide-build-fix.diff`；配置修改前副本位于 `.work2/ide-build-before-20261016/`。

## IDEA 中应用变更

1. Settings → Build, Execution, Deployment → Build Tools → Maven，启用 **Use settings from .mvn/maven.config**；User settings file 和 Local repository 不应被 Override 强制覆盖。项目配置使用 `build-support/maven-settings.xml` 和 `.build-cache/m2`。
2. 右侧 Maven 工具窗口点 **Reload All Maven Projects / 重新加载所有 Maven 项目**，同步完成后执行 **Build → Rebuild Project**；运行入口是 `org.openshell.Main`。

依据：[IDEA Maven 设置文档](https://www.jetbrains.com/help/idea/maven.html)、[Maven 项目配置文档](https://maven.apache.org/configure)。

桌面自动化运行时两次初始化均报告 `failed to write kernel assets: 系统找不到指定的路径`，本轮未完成 IDEA 内点击重新加载和重建验收。IDEA 日志中的自动增量导入仍显示原默认缓存，不能把上面的普通 Maven 构建成功描述为 IDEA 重建已通过。
