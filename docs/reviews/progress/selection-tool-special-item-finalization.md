# 选区工具和特殊物品扩展（2026-11-15）

对应[重构手册第 7 节](../2026-11-13-architecture-refactor-manual.md)。本阶段完成冻结注册与 SelectionTool 的生产接线。服务端模式生命周期及默认输入树属于独立阶段；本记录不将其算作已完成。

## 特殊物品

`server.specialitem.SpecialItemRegistry` 使用 `core.registry.Registry<ResourceLocation, UseOnBlock>`，重复键、空处理器和冻结后注册均失败。`SpecialItemHandlers` 在模组构造期间接收注册，FMLCommonSetupEvent 冻结；默认 debug_stick 仍调用原有方块拾取实现。

附加模组在构造期间注册物品 ID：

```java
SpecialItemHandlers.register(ResourceLocation.parse("example:picker"), (player, position) -> {
   // Execute the item action and return whether it handled the interaction.
   return true;
});
```

资源 ID 接口允许在物品对象尚未注册时声明处理器。原 Item 接口仍可用于已经注册的对象，并检查对象身份。现有 ServerModes／HeldItemServerMode 保持调用 isSpecial／useOnBlock，因此模式识别与实际执行使用同一目录；未新增玩家状态或第二张处理器表。

## SelectionTool

新增 `client.operation.selection.tool`，该包不依赖输入会话、选区编辑器、持久化仓库或 HUD。

- `SelectionTool` 定义 id、geometry、onPress、ready、volume、codec；可声明世界采样、撤回前一选区点、轮换可见性与名称。
- `SelectionToolRegistry` 按 ResourceLocation 注册并拒绝重复。动态 codec 通过工具 ID 选择该工具的 MapCodec，未知 ID、所属 ID／几何类别不符均失败。
- `SelectionTools.register` 在附加模组构造期间接收客户端工具，FMLClientSetupEvent 冻结目录。内置 Cuboid／Prism／ConvexHull／Smart 注册为策略；普通轮换仍先 Cuboid、Smart，再按 ID 添加其他可见工具。
- `SelectionToolState` 保存工具 ID、体积几何类别、点、边界、棱柱基点数、第二点标志和不可变参数。自定义工具可覆写 codec 验证自己的参数。

`OperationSelectionMode` 保留为现有体积表示的几何类别。新增交互工具可以复用一种体积表示，不需要新增枚举项。若要增加一种现有 OperationSelectionVolume 无法表示的体积几何，仍须扩展该公共体积契约。

### 生产接线

`ClientSelectionSession.DraftState` 保存完整 SelectionToolState，并保留已有构造与字段查询接口。会话公开 selectionToolId／setSelectionTool／selectionTool／selectionReady。按压状态机负责原子发布，具体转移交给当前工具；工具不得返回另一工具的状态或将未就绪草稿标为完成。

`SelectionDraftPreview` 与 `SelectionEdits.finishDraft` 共用工具 volume；完成捕获先检查 ready。本地逆操作直接保存完整 DraftState，工具 ID 和参数不会在撤回时被重建为内置工具。工具偏好、空闲进入、清理和 HUD 也使用工具 ID；缺少翻译时 HUD 显示资源 ID。

HUD 只接收 `SelectionToolDisplay` 不可变名称快照。`ClientHudCapture` 在展示装配层捕获 ID／翻译键，`SessionHudText` 不依赖工具业务策略。

智能采样与“撤回前一选区点”使用工具声明的能力。原有异步采样的作用域、revision 校验和捕获清理保持原路径。默认物理输入树的数据化不在本阶段内，后续输入绑定可读取这些能力，不应重新按工具 ID 写中央 switch。

### 草稿格式

ClientOperationDraft.CURRENT_VERSION 从 1 改为 **2**。Selection 字段使用注册工具的 codec，整份草稿、余量和本地逆操作保留完整工具状态。未知工具或无效参数返回读取失败；旧格式走现有 VersionMismatchException 边界。未添加旧磁盘兼容，也未删除未知／旧版本文件。

### 扩展示例证据

SelectionToolExtensionTest 注册了 `example:radius_tool_test`，没有新增枚举项。该工具拥有两次按压的完成规则、按 radius 参数扩展的体积以及参数校验 codec。测试实际进入 ClientSelectionSession，检查按压、ready、预览体积、恢复、偏好、HUD 名称与高亮，并通过 ClientOperationDraftCodec 整份往返。测试工具不加入普通轮换，以免污染其他测试的默认顺序；生产工具默认参与轮换。

## 验证

未运行 Gradle、GameTest 或真实客户端；未暂存、提交、推送或修改 baseline。

使用 JDK 21 和现有缓存依赖，隔离输出至 `.temp/tool-extension-check/classes`：

- 修改的生产闭包、HUD 与 11 个定向测试类编译通过。
- JUnit Platform 直接运行 **50 项，50 成功、0 失败、0 跳过**。
- 新增 SpecialItemRegistryTest、SelectionToolRegistryTest、SelectionToolExtensionTest；迁移 ClientOperationDraftCodecTest 的必填字段断言。
- 回归原有 Cuboid／Prism／ConvexHull 草稿转移、不可变快照、预览、会话生命周期、工具记忆、草稿往返／仓库保护与 IdleModeHudTest。

整批 Gradle 回归、严格架构检查与客户端实测由主代理统一执行。需验证特殊物品实际右键、第三工具进入／轮换／重绑、异步完成、工具状态持久化与断线恢复。直接 JUnit 不替代这些验收。
