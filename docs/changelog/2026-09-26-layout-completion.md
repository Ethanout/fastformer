# 当前 TODO：源码职责与目录整理

上一轮 CODE-01 至 CODE-17 已归档至 [完成记录](../changelog/2026-09-26-code-audit-completion.md)。

范围：整理模块归属，拆分大型入口的独立职责，统一测试路径，增加依赖边界检查。保持现有交互行为和顶层会话设计。

- [x] STRUCT-01：收拢木框、放置上下文和快速替换代码。
- [x] STRUCT-02：整理几何数据与模式、工作区提交模型和编码器、服务端输入入口。
- [x] STRUCT-03：从预览入口提取独立绘制职责；从服务端入口提取输入顺序管理。
- [x] STRUCT-04：测试路径与包名一致；GameTest 集中到专用源码目录，保留开发运行和发布隔离。
- [x] STRUCT-05：增加公共模块与客户端依赖边界、源码路径检查；清理无用导入与根目录临时产物。
- [x] STRUCT-06：执行完整检查、专用服务器测试和迁移差异检查，记录结果。

## 完成记录（2026-09-26）

目录职责见 [源码结构](../source-layout.md)。木框相关代码已归入 `fastplace.placement.effect.woodframe`，共享重复步长算法已从客户端移入 `workspace.transform`，修复共享模块对客户端包的反向依赖。初始方块预览、快速替换预览和服务端输入状态已提取为独立类；大型入口尚有其他协调职责，本轮不宣称已完全拆分。

旧根目录临时产物已移入 `.temp/legacy-artifacts-2026-09-26/`。移动前确认这些文件未被 Git 跟踪，未删除原始内容。`docs/principles/pseudocode/` 未修改。

最终验证命令：

```powershell
.\gradlew.bat clean check runGameTestServer "-PgameTestDirectory=D:/重要的资料/项目/__mc/fastformer/.temp/structure-final-gametest" --no-build-cache --no-daemon
```

- 构建成功；311 个 JUnit 套件，1,836 项测试，0 失败、0 错误、1 跳过。
- 专用服务器 118 项必需 GameTest 全部通过。
- 包路径、共享模块依赖、测试源码隔离、发布 JAR 必需类及测试钩子检查全部通过。
- 迁移前后均有 312 个测试 Java 文件，未丢失文件或测试方法。首次增量构建漏报 17 个移动后的测试类；禁用缓存并全量重编译后恢复完整数量。
- 旧 TODO 归档内容与迁移前版本逐字节一致；`git diff --check` 通过。

日志：`.temp/structure-final-verification.log`。本轮未进行客户端画面人工验收，也未重跑独立恢复进程矩阵。Gradle 仍提示既有的 Gradle 10 兼容性弃用警告。

## 后续：项目与代码根目录

- [x] ROOT-01：保留 `run/`，收拢文档入口与设计草案，归档旧本地产物。
- [x] ROOT-02：恢复脚本归入 `tools/recovery/`，更新项目路径计算与 CI 调用。
- [x] ROOT-03：迁移 `fastplace` 顶层 16 个类，同步调用方和测试，增加顶层包限制。
- [x] ROOT-04：完成全量测试、发布检查与迁移后的恢复脚本验证。

变更说明见 [根目录整理记录](../changelog/2026-09-26-root-layout.md)。
