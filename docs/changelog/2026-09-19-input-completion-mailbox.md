# 输入完成回执 mailbox

提交完成回执现在先进入当前玩家输入会话的 `ClientTickMailbox`。客户端 tick 在固定边界消费这一批事件，再推进输入状态机。会话重置会使队列失效并丢弃迟到回执，物理输入和网络回执不再直接交错写入 routing。

验证：`./gradlew test --tests '*ClientInputSessionTest'` 通过。
