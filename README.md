# FastFormer

NeoForge 1.21.1 创造模式远距离建筑辅助模组，目前处于 alpha 开发阶段。

核心系统：

- 起形：按点、线、面、体逐步生成结构。
- 几何：墙体、多面体、球体、锥柱台体等独立工作流。
- 操作：选区、移动、复制、堆叠和扩展。
- 特殊物品：通过注册入口提供一次性工具行为。

兼容性第一，不使用 mixin。近距离输入交还原版；客户端只负责输入和预览，服务端负责权威状态与世界修改。

开发时依次阅读：

- `SKILL.md`
- `TODO.md`
- `docs/design_principles.md`
- `docs/session_design.md`
- `issues.md`

项目仍不是可发布 demo，脏 worktree 是正常开发状态，不要清理或回退未提交文件。

代码修改后运行：

```powershell
.\gradlew.bat --no-daemon compileJava test --console=plain
.\gradlew.bat --no-daemon deployTo233 --console=plain
```

不自动启动客户端。
