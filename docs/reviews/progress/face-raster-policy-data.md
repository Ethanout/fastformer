# 面栅格策略数据、加载与同步

更新：2026-11-15。保留用户选择：两种面入口分别使用原有形状，`TranslatedScan` 继续保留。

## 默认规则与数据路径

`FaceRasterPolicySet.DEFAULT` 分别捕获 `CURRENT_QUAD`、`CURRENT_POLYGON` 和严格体积面的 `CURRENT_VOLUME_FACE`。`CLASSIFIED` 是显式可选策略，不是生产默认值。

`FastFormer` 注册同步数据包注册表 `fastformer:face_raster_policy`。服务端可在以下文件中覆盖规则：

| 文件 | 作用 |
| --- | --- |
| `data/fastformer/fastformer/face_raster_policy/default.json` | 所有入口的共享覆盖 |
| `data/fastformer/fastformer/face_raster_policy/quad.json` | 四边面入口覆盖，优先于共享覆盖 |
| `data/fastformer/fastformer/face_raster_policy/polygon.json` | 多边面入口覆盖，优先于共享覆盖 |
| `data/fastformer/fastformer/face_raster_policy/volume.json` | 六面体严格面算法覆盖，优先于共享覆盖 |

第一个 `fastformer` 是条目的命名空间，第二个是注册表的命名空间。当前固定读取上述四个条目 ID；其他命名空间的条目不会自动启用。每个文件包含完整的六类链。示例为 `CLASSIFIED`：

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

缺失或校验失败的入口条目先使用有效的共享覆盖；共享覆盖也不可用时，分别使用各入口原有默认值。加载边界 `FaceRasterRule` 记录结构、算法 ID 或空链错误，并将明确的无覆盖标记同步到客户端。原始 JSON 语法错误由 Minecraft 数据包加载器报告。

此注册表沿用 NeoForge 1.21.1 的世界启动加载与登录同步机制。修改规则后需要重启服务端／重新打开世界；不承诺 `/reload` 热更新此注册表。

## 数据接口与生成契约

- `FaceRasterPolicy.read(reader, algorithms)` 严格读取完整 JSON，拒绝重复字段、未知字段／类别／算法、空链、缺项、错误类型、注释和尾随内容。读取器由调用者关闭。
- `parse(JsonElement, algorithms)` 与 `codec(algorithms)` 使用相同结构校验。已经转换为 JSON 树的数据无法再识别原始文本中的重复键。
- `canonicalJson()` 按类别名称排序，使用完整算法 ID，保留链内顺序。`stableHash()` 对 canonical UTF-8 JSON 计算 SHA-256。
- `FaceRasterPolicySet` 保存三个入口策略，hash 包含入口身份和各自的完整链。客户端和服务端按冻结注册表实例缓存快照，不逐帧计算 hash。
- `FacePolicyObserver` 将不可变规则传入同步／异步预览、客户端提交校验、服务端放置计划及复合体积的中间面。中间观察者保留取消能力，不发布未完成的方块。
- 严格体积面仍使用 `RETURN_FAILURE`。原有自带兜底的面适配器不具备严格面资格；不能因链失败而将体积退成线框。

## 预览与写入一致性

`BuildingPreviewParameters` 携带服务端选定规则的 hash。客户端仅在三份预览消息版本一致且本地同步规则 hash 匹配时，组合带规则快照的 `BuildingPreviewSnapshot`。规则变化或参数 hash 不匹配时，立即隐藏旧起形预览、清除可提交快照并递增缓存版本；旧异步结果随缓存失效。随后规则与消息匹配时，才恢复组合。

客户端提交校验与服务端 `PlacementGenerationPlan` 都捕获同一规则内容；已受理任务继续使用自己的冻结快照。服务端确认还比较当前规则 hash 与该预览发布时的 hash，拒绝过期发布。断线重置清除旧连接规则，恢复确认仍经过原有门控。

协议注册版本从 `85` 更新为 `86`，参数消息新增 64 字符 hash。Alpha 不保留旧参数格式兼容。

## 验证

定向测试与完整 `check --no-build-cache --offline` 已通过。新增覆盖：三入口 hash、无效规则同步标记、取消转发、两种消息／规则到达顺序、失配清除旧预览和提交、断线清理、客户端校验与服务端生成同结果、协议 hash 往返。原生产面坐标／遍历顺序与严格体积测试保留并通过。

新世界 GameTest 验证加载注册、共享／入口覆盖和无效共享回退；执行结果见主进度记录。真实客户端联机绘制验收仍未完成。
