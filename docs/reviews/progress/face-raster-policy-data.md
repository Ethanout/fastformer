# 面栅格策略数据

本批提供独立的数据能力，没有更改生产路由、协议或服务端加载器。`CURRENT_QUAD`、`CURRENT_POLYGON` 和 `translated_scan` 保留。

## 数据接口

- `FaceRasterPolicy.read(reader, algorithms)`：严格读取完整 JSON，校验算法注册表；重复字段、未知字段／类别／算法、空链、缺项、错误类型、注释及尾随内容均拒绝。读取器由调用者关闭。
- `parse(JsonElement, algorithms)`：对已有 JSON 树执行相同的结构和注册表校验。原始文本的重复键只能在 `read` 阶段识别。
- `codec(algorithms)`：复用现有 Codec／JsonOps 设施，为后续数据包注册表同步提供同一套校验。调用者传入组装完成的冻结算法注册表。
- `canonicalJson()`：类别键按名称排序，算法 id 使用完整名称；链内顺序保留。
- `stableHash()`：对 canonical UTF-8 JSON 计算 SHA-256，输出 64 位小写十六进制。应在加载／重载时计算并存入策略快照，不需要每帧重复计算。
- 构造时复制 map 和每条链；返回不可变状态，`encode()` 返回独立 JSON 树。

`FaceRasterPolicies.DEFAULT` 的链为：

```json
{"chains":{
  "axis_rectangle":["fastformer:axis_rectangle","fastformer:normal_plane"],
  "coordinate_plane":["fastformer:coordinate_plane","fastformer:normal_plane"],
  "axis_extrusion":["fastformer:axis_extrusion","fastformer:normal_plane"],
  "tilted_parallelogram":["fastformer:normal_plane","fastformer:translated_scan","fastformer:edge_only"],
  "polygon":["fastformer:projected_polygon","fastformer:edge_only"],
  "degenerate":["fastformer:edge_only"]
}}
```

该常量尚未替换当前入口策略。正交轴拉伸以外的轴拉伸会拒绝专用算法并落到 `normal_plane`，保持前一批已确认的形状约束。

## 后续接入建议

现有 `SessionProfiles.register` 通过 `DataPackRegistryEvent.NewRegistry.dataPackRegistry` 同步 Codec 数据；面策略可在公共组装层用相同方式注册 `fastformer:face_raster_policy`，使用上述 registry 绑定 Codec。算法包无需导入网络或服务端类型。

服务端加载边界负责报告错误并选择 `DEFAULT`；解析器本身必须抛出错误，避免静默吞掉配置问题。服务端选定策略后，客户端预览和服务端写入共同使用同一不可变策略快照。快照参数携带其 hash；服务端策略改变或客户端 hash 不一致时，废弃旧预览。单独增加数据注册表同步还不能替代预览快照的失效逻辑。

## 验证状态

新增测试尚未执行，按协作安排由 root 串行运行 Gradle：

```text
--tests io.github.fastformer.fastplace.geometry.generation.face.policy.FaceRasterPolicyTest
--tests io.github.fastformer.fastplace.geometry.generation.face.FaceRasterCancellationTest
```

覆盖严格读取、Codec 往返、默认链、未知 id、empty 链、缺项、重复键、map 顺序不影响 hash、链顺序影响 hash、输入／输出不可变，以及候选间、返回 Filled 后和 OUTLINE 前的取消边界。建议同时回归 `FaceRasterDispatcherTest`、`FaceRasterAdaptersTest` 和 `SpecializedFaceRasterTest`。
