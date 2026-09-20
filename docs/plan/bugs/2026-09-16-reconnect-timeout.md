# 重连等待超时误删草稿

`onClientTick` 在 40 tick 内未收到快照时调用 `discardCurrentDraft`。后者同时删除暂存数据与磁盘文件。网络延迟或服务器暂时无响应不能证明草稿失效。

主代理删除超时分支的丢弃调用。超时仍结束短期连接屏障，草稿保留。迟到的活动快照可通过现有 `shouldHoldStoredDraft` 路径进入恢复确认。

显式关闭恢复提示和服务端 inactive 快照的处理尚未改动，需要继续检查初始 inactive 快照与后续活动快照的顺序，不能假定它们等同用户取消。

定向运行 `ClientOperationControllerTest` 与 `ClientDraftLoadTest` 时，编译被正在修改的 `WorkspacePreviewComposer` 语法错误阻断。当前没有此次修复的测试通过证据，待缓存代理交付后统一重跑。仍需新增带真实暂存草稿的迟到快照回归用例。

后续验证：新增 `unansweredReconnectKeepsTheStagedDraft`，暂存含首点的真实草稿，经过 200 tick 无响应后检查草稿仍存在且可以恢复首点。修正测试遗漏的异常声明后，上述两个测试类定向运行成功。磁盘文件保留和真实网络迟到快照仍需要进一步验收。
