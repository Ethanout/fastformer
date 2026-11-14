# 架构检查

`gradle/architecture.gradle` 保留源码布局检查，并通过独立工具检查实际编译后的类型依赖。工具源码在 `tools/architecture/src/`，不进入模组产物，也不加载 Minecraft。依赖为 ASM 9.8 和 ASM Commons 9.8，当前环境可离线解析。当前未引入 ArchUnit；工具实现同等方向的字节码检查，不能据此声称手册中的 ArchUnit 条目已完成。

## 常用命令

- `gradlew.bat --offline testArchitectureTool`：独立测试检查器，不要求生产代码可编译。
- `gradlew.bat --offline architectureReport`：编译生产代码并生成完整报告，不更改基线，也不以报告任务通过表示架构达标。
- `gradlew.bat --offline verifyArchitecture`：拒绝基线以外的新问题，并拒绝所有严格能力边界违规。已接入 `check`。
- `gradlew.bat --offline verifyArchitectureStrict`：完整验收，不接受任何历史基线问题。只有该命令通过，才能声称本工具覆盖的架构约束全部达标。
- `gradlew.bat --offline updateArchitectureBaseline`：审阅报告后显式更新迁移基线。不会由 `check` 调用。不得用它接受新缺陷或代替修复。

每个命令的报告位于 `build/reports/architecture/<模式>/`，含 `summary.txt` 和 `architecture.json`。JSON 列出全部类型依赖、客户端包图、新问题、已解决问题和剩余问题。只要还有问题，摘要的完整验收结果就是 `NOT ACHIEVED`。阶段结束时应删除基线中报告为 `resolvedDebt` 的条目，防止已修复问题再次被接受。

## 检查范围

编译字节码提供继承、接口、泛型签名、字段和方法类型、注解、类型常量、方法调用、字段访问、强制转换、异常及动态调用句柄中的依赖。内部类依赖归入所属外部类型，避免把一个源文件误算成多个包内类型。普通字符串中的类名不算静态依赖；反射生成的运行时依赖不在本检查范围内。

立即严格执行，不能加入迁移基线：

- `client.input` 不依赖 `client.render`。
- `client.feedback` 不依赖 `client.render`、`client.input` 或 `client.session`。

最终验收约束：

- 分层只允许同层或按 `client/server → protocol → common → core` 方向引用。`core` 不依赖 Minecraft/NeoForge；`common`、`protocol` 不依赖 Minecraft 的客户端或服务端类型。旧 `fastplace`、`workspace` 等包不视为已迁移领域层。
- `client` 与 `server` 互不依赖。
- 客户端精确包图不能有环。采用强连通分量分析，报告环内每条实际包依赖边，不只找一个示例环。
- 每个包至多 12 个顶层类型，并具有 `package-info.java`。
- 单类型包默认不允许。确为独立系统公开入口时，在包说明中用 `@architecture-api-entry` 明示原因。该标记只豁免单类型包，不豁免其他约束。
- 类型名不以 `Manager`、`Dispatcher`、`Core`、`Owner`、`Semantics` 结尾。唯一精确命名例外为手册第 10 节明确指定的 `common.geometry.raster.face.FaceRasterDispatcher`：只分派面光栅算法，没有输入、会话或渲染职责；该例外不适用于其他包或其他 Dispatcher。
- 生产源文件至多 800 行；算法类例外必须在源码用 `@architecture-large-algorithm` 标注原因。输入／预览两个旧总入口仍存在时必须至多 200 行，不能用算法标记豁免。

迁移基线 `gradle/architecture-debt.txt` 逐条记录类型边、环内包边和布局问题；禁止通配符。基线仅防止迁移期间新增问题，不代表已有问题合理，也不代表手册已经完成。完整验收任务完全忽略基线。

## 检查器自身验证

工具自测用 ASM 生成最小字节码，验证泛型、注解、方法签名、类型常量、类型转换、直接调用和 lambda 句柄不会漏检；验证普通字符串不会制造假依赖；验证内部类合并和真实强连通分量。临时目录测试同时验证基线不会接受新边、严格模式拒绝旧债、基线不能豁免严格边界，以及验证过程不会静默改写基线。

## 首次实际结果（2026-11-14）

`.temp/architecture-bytecode-report.log` 对应的一次成功生产编译，共读取 956 个顶层类型，报告 1983 条迁移问题：分层依赖 998 条，客户端环内包边 280 条，超限包中的类型 287 个，未迁移类型 266 个，缺包说明 82 个，单类型包 38 个，旧后缀 22 个，超过 800 行的源文件 8 个，超过 200 行的旧总入口 2 个。两个立即严格能力边界均为 0。

这份结果证明检查已经覆盖实际工程，也证明整项架构迁移尚未达标。数字对应当次编译，后续以最新报告为准。不能把类别数字相加后称为“独立缺陷数”：同一类型可能同时违反多个约束。
