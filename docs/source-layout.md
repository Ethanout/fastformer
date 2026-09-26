# 源码结构

Java 包统一位于 `io.github.fastformer` 下。按职责放置代码，测试使用与被测代码一致的包路径。

模组包顶层只保留 `FastFormer` 入口。`fastplace` 顶层不直接存放 Java 类。

| 包 | 职责 |
| --- | --- |
| `client` | 客户端输入、交互状态、界面和预览 |
| `server.input` | 服务端输入分发、请求顺序与手势身份 |
| `server.session` | 快速起形、几何和选区操作的服务端会话管理 |
| `fastplace.session` | 会话数据、活动类型和交互状态 |
| `fastplace.settings` | 放置与交互设置 |
| `fastplace.text` | 翻译文本契约与玩家消息 |
| `fastplace.interaction` | 空手方块操作和特殊物品交互 |
| `fastplace.geometry.raycast` | 远距离方块射线查询 |
| `fastplace.placement.context` | 放置上下文和可放置物品判断 |
| `fastplace.placement.effect.woodframe` | 木框朝向与放置效果 |
| `fastplace.placement.replace` | 快速替换与去重 |
| `fastplace.geometry` | 几何数据与算法；锥体、多面体和生成规则放在各自子包 |
| `workspace.submission` | 工作区提交计划、校验与冲突语义 |
| `workspace.transform` | 共享选区变换与重复步长算法 |
| `network.codec` | 网络数据编码与解码 |

`FastPlaceClientPreviewCore` 将初始方块预览与快速替换绘制委托给独立渲染类。`ServerInputDispatcher` 将请求水位与手势身份保存在 `ServerInputState` 中。这两个入口仍有其他协调职责，本轮没有完全拆分。

## 源码集与检查

- `src/main/java`：发布源码。
- `src/test/java`：JUnit 单元测试。
- `src/gametest/java`：游戏集成测试与恢复进程测试。开发运行加载该源码集，发布 JAR 不包含它。
- `gradle/architecture.gradle`：检查包路径、生产代码与测试入口隔离，以及 `workspace`、`network.codec`、`fastplace.geometry` 对客户端类的源码依赖。此检查不是完整的字节码依赖分析。
- `gradle/release.gradle`：发布时移除测试钩子并检查产物。

运行 `./gradlew.bat check` 执行单元测试、测试源码编译、目录与依赖检查、发布包检查。运行 `./gradlew.bat runGameTestServer` 执行游戏集成测试；可用 `-PgameTestDirectory=<目录>` 指定独立运行目录。

临时脚本和诊断产物放在已忽略的 `.temp/` 下。旧根目录产物保存在 `.temp/legacy-artifacts-2026-09-26/`，不作为源码参与构建。

## 项目根目录

根目录保留 `README.md`、许可证、Gradle 构建文件、源码、文档、开发工具与工具配置。Minecraft 开发运行仍使用常规的 `run/`。Gradle 输出与缓存仍使用 `build/` 和 `.gradle/`。

当前任务和问题分别集中在 `docs/plan/current_todo.md` 与 `docs/plan/design_questions.md`。未实施的设计草案放在 `docs/plan/proposals/`。旧 `issues.md` 只声明没有阻塞问题，未包含需迁移的问题条目。

恢复验证脚本位于 `tools/recovery/`，体素实验位于 `tools/voxel_lab/`。原根目录 `bin/`、`logs/` 和 `recovery/` 内容保存在 `.temp/legacy-artifacts-2026-09-26/root-cleanup/`。这次归档没有更改第三方工具的默认输出路径。
