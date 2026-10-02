# 无支撑方块的放置朝向

几何手按以下顺序确定方块状态：

1. 使用真实环境中的原版放置状态。
2. 失败时，按首点点击面与视线顺序，逐面模拟一个石头支撑，并重试原版放置状态。
3. 虚拟支撑仍失败时，使用数据包回退规则。
4. 数据包也无法给出状态时，直接使用方块默认状态。

手持方块、空闲状态下的首次中键点击默认嵌入命中的方块格。其他起点点击仍按原有 Alt 规则选择表面或嵌入。
嵌入时，状态计算直接使用嵌入位置的邻接方块和流体，不复用外侧放置位置的状态。点击面与命中位置保持真实。

规则从方块默认状态开始设置属性。物品的 `minecraft:block_state` 组件最后应用，其显式属性优先。
没有匹配规则时，保留方块默认状态。规则不会改变滚轮操作、栅栏连接或邻接更新设置。

## 虚拟支撑

每次尝试只覆盖一个相邻位置的方块、流体和方块实体读取。目标格及其他位置保持真实。
支撑只在本线程、本世界的当前查询中有效。返回或抛出异常时都会恢复读取，不在世界中放置支撑方块。
候选支撑不超出世界高度与坐标边界，也不接受物品重定向到其他目标格的重试结果。

虚拟支撑会重试物品路径，以保留火把、告示牌和旗帜的壁挂变体。
一次只尝试一个支撑，避免空中的钟直接选择双侧安装，或多面附着方块同时连接多个虚构邻居。
真实环境中的有效结果优先，栅栏连接和无支撑也能正常生成的状态沿用原版。

特殊材质要求仍由原版判断。例如可可果要求丛林原木，虚拟石头不能满足此要求，流程会继续进入数据包回退。
当前版本不通过数据包修改虚拟支撑材质，数据包仅配置第三步的属性回退。

## 默认数据包规则

下列规则只在真实环境和虚拟支撑都失败后使用。

| 方块 | 无支撑时的回退 |
| --- | --- |
| 梯子 | 正面朝向玩家，朝向取自首点建立时的水平视线。目标格是水源时保留含水状态。 |
| 按钮、拉杆 | 按点击面选择地面、天花板或墙面安装。墙面朝向取点击面，地面和天花板朝向取首点水平视线。 |

正常靠墙放置仍由原版决定。确定首点后，玩家转身不会改变已捕获的朝向输入。
预览和实际放置读取相同规则。回退只决定放置状态，不提供永久支撑保护。后续世界更新仍可能使无支撑方块掉落。

## 数据包目录

在世界的 `datapacks` 目录中创建一个数据包：

```text
placement-rules/
  pack.mcmeta
  data/
    fastformer/
      fastformer/placement_fallback/ladder.json
      tags/block/placement_fallback/ladder.json
```

Minecraft 1.21.1 的 `pack.mcmeta`：

```json
{
  "pack": {
    "pack_format": 48,
    "description": "几何手放置朝向规则"
  }
}
```

规则属于同步的数据包注册表 `fastformer:placement_fallback`。路径中的两个 `fastformer` 分别是规则命名空间和注册表命名空间。
服务端在加载世界时读取规则，并将规则同步给客户端。客户端不需要安装同一份数据包。
修改规则 JSON 后，单人游戏退出并重新进入世界，专用服务器重启。仅执行 `/reload` 不会重建此注册表。
方块 tag 可以随 `/reload` 更新，但同时修改规则时应重新加载世界。

## 覆盖默认规则

默认梯子规则位于 `data/fastformer/fastformer/placement_fallback/ladder.json`：

```json
{
  "block_tag": "fastformer:placement_fallback/ladder",
  "properties": {
    "facing": "$player_horizontal_opposite",
    "waterlogged": "$waterlogged"
  }
}
```

在优先级更高的数据包中放置同路径文件，即可替换整条规则。例如将 `facing` 改为 `"east"`，梯子在进入数据包回退时便固定朝东。
数据包规则不覆盖前两步的成功结果。禁用规则也不会禁用虚拟支撑。

要禁用这条默认规则，使用：

```json
{
  "block_tag": "fastformer:placement_fallback/ladder",
  "enabled": false
}
```

另一条默认规则为 `fastformer:face_attached`，匹配 tag `fastformer:placement_fallback/face_attached`。
该 tag 默认包含 `#minecraft:buttons` 和 `minecraft:lever`。

## 扩展到其他模组方块

具有相同属性的方块可以加入现有 tag。下面的文件路径是 `data/fastformer/tags/block/placement_fallback/ladder.json`：

```json
{
  "replace": false,
  "values": [
    { "id": "example:custom_ladder", "required": false }
  ]
}
```

`example:custom_ladder` 是示例 ID。它需要同时具有 `facing` 和 `waterlogged` 属性，才能使用默认梯子规则。
不含水的方块应使用单独的 tag 和规则，只配置 `facing`。

自定义规则放在 `data/<你的命名空间>/fastformer/placement_fallback/<规则名>.json`。
`block_tag` 使用 tag ID，不带 `#`。tag 文件内引用其他 tag 时仍使用 `#`。

## 规则字段

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| `block_tag` | 必填 | 规则匹配的方块 tag。 |
| `priority` | `0` | 数值越高越先尝试。相同优先级按完整规则 ID 的字典序排列。 |
| `enabled` | `true` | `false` 时跳过本条规则。 |
| `properties` | `{}` | 属性名与属性值的映射。 |

只应用第一条匹配且属性兼容的规则，不合并多条规则。
若属性不存在，或属性不接受计算出的值，则整条规则跳过，继续尝试下一条。
空 `properties` 是有效规则：匹配时保留默认状态，并停止尝试后续规则。

## 属性值

普通字符串是方块属性的原始值，例如 `"north"`、`"floor"`、`"false"`、`"3"`。
以 `$` 开头的字符串从放置上下文取值：

| 表达式 | 结果 |
| --- | --- |
| `$player_horizontal` | 首点水平视线方向。 |
| `$player_horizontal_opposite` | 首点水平视线的反方向。梯子用此值朝向玩家。 |
| `$clicked_face` | 首点点击面方向。 |
| `$clicked_face_opposite` | 首点点击面的反方向。 |

### 三维视线与安装方式

| 表达式 | 结果 |
| --- | --- |
| `$nearest_look` | 首点最接近的三维视线方向。 |
| `$nearest_look_opposite` | 首点最接近的三维视线反方向。 |
| `$attachment_face` | 点击上面取 `floor`，下面取 `ceiling`，侧面取 `wall`。 |
| `$attachment_facing` | 点击侧面取点击面方向，否则取首点水平视线方向。 |

`$waterlogged`：目标格流体是原版水源时取 `true`，否则取 `false`。

未知 `$` 表达式会使规则解码失败，并报告数据包错误。方向名称不代表所有方块都具有相同的正面含义，请按方块属性配置。
