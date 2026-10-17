# 首页最近连接整行点击修复

原来的最近连接条目是 JPanel，只有右侧按钮注册了连接动作。现在使用单个 JButton 覆盖整行，主机图标、文字、空白区和右侧“连接”提示都使用同一个连接动作；没有嵌套子按钮。

- 整行显示手型与悬停高亮，支持 Enter 和空格操作，提供主机名与地址的辅助访问说明。
- 保留最近连接排序、右侧“连接”提示和原有 `openSession` 入口；右键不触发连接。
- 三项新增测试覆盖整行五个位置的点击及每次仅调用一次、右键排除、键盘操作，以及深浅主题下空白区域的悬停反馈。测试用回调计数验证连接请求，没有连接用户服务器。
- 修复前重现条目主体不响应和缺少 Enter 操作。最终 `mvn -B -ntp clean verify` 共 55 项测试通过；150% 缩放、非 headless 环境下三项定向测试通过。已检查条目组件的深浅主题图片，未进行原生桌面鼠标点击验收。

产物 `target/openshell-1.0.0.jar`：6,997,728 字节，SHA-256 为 `d71ae81bbb8f5570428d04cdde1ed3744e29b5175332e3c4e4e5a04d76674471`。

日志为 `target/verification/welcome-row-build.log`、`welcome-row-scale.log`；预览为同目录的 `welcome-row-dark-hover.png` 和 `welcome-row-light.png`。修改前的 MainWindow 与 JAR 位于工作区 `.work2/welcome-row-before-20261017`。
