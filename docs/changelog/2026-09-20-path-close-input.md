# 路径闭合输入职责拆分

PathCloseGesture 保存单个输入会话的双击配对。PathCloseInputDispatcher 负责闭合条件与网络请求。FastPlaceClientInput 保留输入路由，删除原来的闭合实现和三个散落的状态字段。

鼠标与交互回调记录发生时间，闭合判断使用该时间，不在处理途中重新计时。坐标保存为不可变值。不同目标、不同会话类型、超时、逆序时间及取消重置不能形成双击。闭合成功后清除配对，第三次点击开始新配对。

新增五项 PathCloseGestureTest，更新输入会话重置测试。完整 `test check` 通过，日志见 `.dsh-tmp/path-close-integration.log`。

闭合请求仍由兼容同步鼠标路径发送，尚未完成快速起形和特殊形状的鼠标队列迁移。本次未执行客户端实机验收。
