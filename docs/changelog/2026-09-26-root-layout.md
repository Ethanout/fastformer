# 项目与代码根目录整理

## 目录变更

- 删除仅作跳转的根目录 `TODO.md`。当前清单仍在 `docs/plan/current_todo.md`，README 提供入口。
- 删除不含问题条目的根目录 `issues.md`。设计问题入口统一到 `docs/plan/design_questions.md`。
- 将扫掠与放样草案移至 `docs/plan/proposals/sweep-loft.md`，仅调整标题，保留设计内容。
- 将恢复验证脚本移至 `tools/recovery/Test-RecoveryRestart.ps1`，修正项目根目录计算并更新 CI 调用路径。
- 将旧 `bin/`、`logs/`、`recovery/` 和空的 `network/`、`scripts/` 归档到 `.temp/legacy-artifacts-2026-09-26/root-cleanup/`。原内容保留。

保留常规的 `run/`、Gradle 输出与缓存目录。`docs/principles/pseudocode/` 未修改。空的 `.agents/` 与 `.codex/` 保留：自动审批拒绝了包含空目录删除的组合命令，未提供具体原因。

## Java 包变更

模组顶层只保留 `FastFormer`。`fastplace` 顶层原有 16 个类已迁移，路径与包名一致。

| 新包（省略 `io.github.fastformer`） | 类 |
| --- | --- |
| `server.session` | FastPlaceManager、GeometryManager、OperationManager |
| `fastplace.session` | FastPlaceActivity、InteractionState |
| `fastplace.settings` | FastPlaceSettings |
| `fastplace.text` | FastPlaceMessages、TranslatableText |
| `fastplace.geometry` | FillMode |
| `fastplace.geometry.generation` | FastPlaceGeometry |
| `fastplace.geometry.raycast` | LongRangeBlockRaycast |
| `fastplace.selection` | OperationPointDragConstraint |
| `fastplace.placement` | PlacementUpdateMode |
| `fastplace.interaction` | BlockTinker、SpecialItemHandlers |
| `workspace.submission` | WorkspaceAdmission |

相关单元测试和需要包内访问的 GameTest 随职责迁移。设置读取与消息发送中三个跨包调用方法改为公开方法，测试专用接口仍保留原有可见性。构建中的发布包类路径检查已同步更新。

`verifySourceLayout` 新增两条规则：模组顶层只能放入口类，`fastplace` 顶层不能直接放功能类。

GameTest 源码集显式使用唯一目录 `src/gametest/java`，避免默认目录 `src/gameTest/java` 与指定目录在 Windows 上形成大小写重复。

## 验证

- `clean check runGameTestServer --no-build-cache --no-daemon` 使用独立目录 `.temp/root-layout-gametest`，构建成功。
- 311 个 JUnit 套件、1,836 项测试，0 失败、0 错误、1 跳过。118 项必需 GameTest 全部通过。
- 显式指定 GameTest 唯一目录后再次执行 `check`，目录、依赖边界、发布 JAR 与测试钩子检查通过。
- 新路径下的恢复脚本通过 `FirstWrite` 场景，实际完成崩溃与重启校验。崩溃阶段的 Gradle 失败是测试预期，验证阶段成功，脚本退出码为 0。本轮未重跑其他六个恢复场景。
- `git diff --check` 通过，顶层会话设计目录无差异。未执行客户端画面人工验收。

日志位于 `.temp/root-layout-verification.log`、`.temp/root-layout-final-check.log` 和 `.temp/root-layout-recovery.log`。
