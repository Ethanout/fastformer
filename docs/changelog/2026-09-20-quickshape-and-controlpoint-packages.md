# 快速起形与控制点归类

快速起形的 FastPlaceStage、FastPlaceMode、PointMode、LineMode、FaceMode、VolumeMode、RaycastPlacement、PolygonVolumeShape 从 fastplace 根包移至 fastplace.quickshape。更新客户端、服务端、网络及测试引用。枚举值、顺序、名称和翻译键保持原值，未改变协议和保存格式。资源、脚本及文档没有旧全限定类名引用。

ControlPointPresentation 归入 client.controlpoint，负责快速起形与选区控制点、候选方块及闭合提示的显示值构建。原预览核心移出约 100 行逻辑。选区渲染器显式传入悬停点索引，显示构建不再回读全局输入状态。返回的控制点列表不可变。

两项改动分别通过完整 `test check`，最终集成日志为 `.dsh-tmp/quickshape-package-integration.log`。本次没有重跑 GameTest，没有进行客户端实机验收。完整鼠标队列迁移、预览流水线与其他大类拆分仍未完成。
