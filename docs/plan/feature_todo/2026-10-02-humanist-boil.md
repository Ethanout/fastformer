# 人文主题 boil 重做

目标：动态线条像手绘动画逐帧重画，动感明显，但不蠕动、不闪烁。静态线条完全不动。

## 改动前的问题

- `PencilStroke.draw` 把一条边切成最多 24 段，按 `sin(seed + i*2.4)` 做横向偏移，`seed` 含连续递增的帧号，形成沿线传播的行波，看起来像在蠕动。
- `pencil_far_boil` 按距离把幅度放大到 23.5 倍。幅度是世界单位，远处更夸张。
- `pencil_pigment.glsl` 和 `preview_material.fsh` 的颗粒用 `BoilFrame`（0～1023）做种子，每帧整片重洗，产生闪烁。

## 原则

1. 画稿轮换：动态部分只在 N 张"画稿"（默认 3）之间切换，频率为 `boil_fps`。每次随机换到另一张，不重复上一张。硬切，不插值，不用连续相位。
2. 线保持直。每张画稿只允许角点整体微移和一个可选的单弧微弯，不做多峰起伏。
3. 角点共享：同一角点在同一画稿下的偏移完全一致，相邻两条边不裂角。
4. 偏移按屏幕像素计，远近观感一致。
5. 静态部分固定使用画稿 0，零偏移，与当前静态效果一致。

## 改动

### 1. 新增 `client/render/geometry/BoilClock.java`

- `static int sheet(boolean dynamic)`：静态返回 0。动态时用 `k = floor(nanoTime / 1e9 * boil_fps)`，当 `k` 变化时从 `1..N` 中随机选一个不等于上一张的值，并缓存当前值（客户端渲染线程单线程，静态字段即可）。
- N 读取 `VisualThemes.value("boil_sheets", 3)`，最小为 2。
- 同一帧内所有调用方都拿到同一个值。

### 2. 新增 `client/render/geometry/BoilJitter.java`（纯函数，便于单测）

- `static Vec3 cornerOffset(Vec3 corner, int sheet, double worldPerPx, double jitterPx)`：`sheet == 0` 或 `jitterPx <= 0` 时返回 `Vec3.ZERO`。否则用（角点坐标按 1/4096 取整，sheet）做 SplitMix 哈希，得到一个各分量在 [-1, 1] 内的向量，再乘 `jitterPx * worldPerPx`。
- `worldPerPx = 2 * distance * tan(fov / 2) / screenHeight`，由调用方根据相机和窗口计算。

### 3. 改写 `PencilStroke.draw` 的人文分支

- 删除正弦分段、`phase`、`wobble` 和远处增益。
- `sheet = BoilClock.sheet(dynamic)`。
- 先对原始 `from`、`to` 各加 `BoilJitter.cornerOffset(...)`，再计算 overshoot 和延长后的端点。必须先抖角点再延长，这样共享角点的两条边偏移一致。
- 可选单弧：`boil_bow_px > 0` 且 `sheet > 0` 时，把线切成 4 段，中点横向偏移 `bowPx * worldPerPx * (2*hash(线, sheet) - 1)`，按抛物线 `4t(1-t)` 分配。否则只画 1 段。
- 经典分支保持不变。

### 4. `PencilStrokeEnds.forLine` 增加 `int sheet` 和 `double variation` 参数

- `sheet == 0` 时结果必须与现在完全一致（现有测试不应改动）。
- `sheet > 0` 时，用 `mix(seed ^ mix(sheet))` 得到 `r2`，两端长度各乘 `1 + variation * (2*r2 - 1)`。长短端的位置不变，只改变长度。
- `variation` 读取 `boil_overshoot_variation`，默认 0.08。

### 5. 着色器颗粒只跟随画稿

- `FastPlaceClientShaders.configurePencil` 和 `PreviewMaterialRenderer` 中设置 `BoilFrame` 的地方都改为 `BoilClock.sheet(dynamic)`。uniform 名称保持不变，避免改动 JSON 和资源包接口。
- 着色器代码不需要改，种子只剩 N+1 种取值，颗粒随画稿切换，不再每帧重洗。

### 6. 主题参数

`humanist.json`：

- 删除 `pencil_wobble` 和 `pencil_far_boil`。
- 新增 `"boil_sheets": 3`、`"boil_jitter_px": 0.25`、`"boil_bow_px": 0.08`、`"boil_overshoot_variation": 0.08`。
- `boil_fps` 保持 8，可以试 6。

`classic.json`：删除 `pencil_wobble`。`boil_jitter_px` 和 `boil_bow_px` 设为 0。

同步更新 `docs/visual-resources.md` 中的参数列表。

## 测试

在 `src/test/java/.../client/render/geometry/` 下：

- `PencilStrokeEndsTest`：
  - sheet 0 结果与旧行为一致。
  - 同一 sheet 结果确定。
  - 不同 sheet 长度不同，且在 ±variation 范围内。
  - 长端位置不随 sheet 改变。
  - 端点顺序互换后结果对称。
- `BoilJitterTest`：
  - sheet 0 返回零。
  - 同一角点同一 sheet 结果一致。
  - 换 sheet 后结果变化。
  - 长度不超过 `sqrt(3) * jitterPx * worldPerPx`。
- `BoilClock` 如果依赖 `nanoTime` 难以测试，就把"选下一张"抽成 `static int next(int previous, int n, long k)` 单独测：结果不等于 previous，且在 `1..n` 范围内。

## 预览素描着色器（`preview_material.fsh`）

现状：每个面都有同一套等距、等宽、连续的斜纹，叠在原材质色上，再加一层逐像素噪点。它和明暗无关，没有轮廓，看起来像印花布，不像素描。

改法，按优先级：

1. 明暗决定排线层数（tonal art map）。用 `luma(material) * faceShade` 算出调子，faceShade 取 MC 的面亮度：顶 1.0，南北 0.8，东西 0.6，底 0.5。
   - 亮：不画排线，留纸白。
   - 中：一层排线。
   - 暗：叠第二层交叉排线。
   - 最暗：第三层，加密。
   - 每层用 smoothstep 淡入，不要硬阈值。
2. 线要断、要不均匀。
   - 按排线行号 `floor(phase)` 取哈希，得到每行的宽度（0.6～1.2 倍）、起点偏移和断开位置，沿线方向切成 0.3～0.8 格长的一段段。
   - 每段两端加 taper，体现起笔和收笔。
   - 每行角度加 ±4° 的哈希扰动。
   - 三个平面使用不同的基础角度，比如 35°、-50°、70°，避免三面纹路一致。
3. 去色、贴近纸面。
   - 底色按 `sketch_desaturate`（默认 0.6）向灰度靠拢，再整体提亮，接近纸色。
   - 排线颜色用深石墨色 `sketch_graphite`，不要用材质色乘暗。
   - 经典主题不走这套。
4. 轮廓线。
   - 在同一 pass 里，对 `PreviewDepth` 和法线做 3×3 邻域差分：深度突变或法线突变处画深色轮廓，宽 1.5～2px，带少量断续。
   - 方块之间的接缝法线相同、深度连续，不会出线，符合"只画外轮廓"的规则。
5. 去掉逐像素 `pigment` 噪点，改为 `sketch_paper_scale` 控制的低频纸纹，只影响排线的浓淡，不直接改底色。
6. Boil：所有哈希的种子都只用画稿号。按上文第 5 步，`BoilFrame` 会改为画稿号。确认部分固定为 0，待定部分随画稿换笔。排线仍按世界坐标和面法线锚定，不随相机滑动。

新增参数（`humanist.json`，资源包可覆盖）：`sketch_desaturate`、`sketch_graphite`（rgb）、`sketch_layers`（默认 3）、`sketch_outline_px`、`sketch_paper_scale`、`hatch_angle_jitter`。保留 `hatch_strength` 和 `hatch_scale`。

验收：在明亮的草地和暗的洞穴两种背景下各截一张图，对比 `.temp/style-reference/`。重点看：
- 亮面基本留白，暗面有交叉排线。
- 线是断的。
- 有外轮廓。
- 转动视角时排线不滑动。

## 验证

- `gradlew check`。
- `runClient`，人文主题下逐项看：
  - 快速形状、选区、智能选区、几何、工作区的候选和拖动状态：每秒能明显看到重画，没有蠕动和闪烁。
  - 角点不裂。
  - 远近抖动幅度相同。
  - 确认后的静态线条完全不动。
- 日志写入 `.temp/`，不提交 Git。

## 实现状态

- [x] 共用画稿时钟，渲染帧开始时锁定编号。
- [x] 共享角点偏移、单弧微弯和长短端变化。
- [x] 线条与材质使用同一画稿，静态部分使用画稿 0。
- [x] 主题参数和资源包／附属模组接口文档同步。
- [x] `gradlew check` 通过，画稿／角点／延伸共 11 项测试通过。
- [x] 客户端启动，FF 着色器加载无报错。
- [ ] 游戏内检查远近动感、角点、静态线和大型选区性能。

偏移使用逻辑 GUI 像素，与线宽缩放一致。投影矩阵提供当前 FOV，视空间深度决定每像素的世界长度。
微弯限制在两个共享角点之间，延伸段沿端点方向接续，避免弧线在交点处裂开。
日志：`.temp/humanist-boil-check.log`、`.temp/humanist-boil-client.log`。画面验收仍需游戏内完成。

### 线宽与动画反馈

以全屏截图 `.temp/style-reference/humanist-line-width-reference.png` 为线宽基准。
主线由 2.25 调为 1.25 逻辑 GUI 像素，近处加粗倍率由 1.6 调为 1.15，描边同步收细。
角点抖动由 1.2 调为 0.25，微弯由 0.4 调为 0.08，动态延伸变化由 30% 调为 8%。画稿频率保持每秒 8 次。
编译和客户端着色器加载通过。日志：`.temp/humanist-soften-client.log`。实际粗细仍需对照全屏截图检查。
