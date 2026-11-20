package com.agent4.worker; // 验证消息边界，不把永久错误与暂时错误混在一起。
import com.agent4.backend.*; // 替换数据库和 Redis，不调用真实模型。
import com.agent4.contract.Contracts.*; // 使用正式消息与任务协议。
import com.fasterxml.jackson.databind.ObjectMapper; // 真实序列化版本化消息。
import com.rabbitmq.client.Channel; // 检查 ACK、NACK 和死信选择。
import org.springframework.amqp.core.*; // 创建接收消息。
import org.junit.jupiter.api.Test; // Worker 协议回归测试。
import static org.mockito.Mockito.*; // 只 Mock 外部设施与数据库边界。
import java.util.Optional; // 模拟领取竞争失败。
import org.springframework.test.util.ReflectionTestUtils; // 为未启动 Spring 的消费者注入服务端运行时配置。
import org.mockito.ArgumentCaptor; // 校验持久化结算使用的是 Harness 正式结果。
import static org.junit.jupiter.api.Assertions.*; // 验证业务成功与结果内容。
class TaskConsumerTest { // 不需要额外部署消息服务。
    Message message(byte[] body) { MessageProperties properties = new MessageProperties(); properties.setDeliveryTag(7); return new Message(body, properties); } // 固定投递标识便于断言。
    @Test void malformedMessageIsDeadLettered() throws Exception { // 非 JSON 消息不能无限重投。
        TaskStore tasks = mock(TaskStore.class); Channel channel = mock(Channel.class); // 无需创建任何执行资源。
        TaskConsumer consumer = new TaskConsumer(tasks, mock(RedisSlots.class), mock(KnowledgeStore.class), new ObjectMapper()); // 使用真实消费者控制流。
        try { consumer.consume(message("broken".getBytes(java.nio.charset.StandardCharsets.UTF_8)), channel); verify(channel).basicReject(7, false); verifyNoInteractions(tasks); } finally { consumer.close(); } // 协议错误直接进入死信。
    } // 结束非法消息测试。
    @Test void duplicateClaimReleasesCapacityAndAcknowledges() throws Exception { // 其他 Worker 已领取时不能重复调用模型。
        TaskStore tasks = mock(TaskStore.class); RedisSlots slots = mock(RedisSlots.class); Channel channel = mock(Channel.class); ObjectMapper json = new ObjectMapper(); // 只模拟竞争结果。
        Task task = mock(Task.class); when(task.id()).thenReturn("task"); when(task.userId()).thenReturn("user"); when(task.state()).thenReturn("QUEUED"); // 消息接收时看到的快照可能已经过时。
        when(tasks.get("task")).thenReturn(task); when(slots.acquire(eq("user"), anyString())).thenReturn(true); when(tasks.claim(eq("task"), anyString())).thenReturn(Optional.empty()); // 模拟另一个 Worker 抢先成功。
        TaskConsumer consumer = new TaskConsumer(tasks, slots, mock(KnowledgeStore.class), json); // 构造真实消费者。
        try { consumer.consume(message(json.writeValueAsBytes(new Dispatch(1, "task", "trace"))), channel); verify(channel).basicAck(7, false); verify(slots).release(eq("user"), anyString()); } finally { consumer.close(); } // 重复消息 ACK 并精确归还自己的容量。
    } // 结束重复消费测试。
    @Test void claimedTaskRunsThroughHarnessBeforeSettlement() throws Exception { // 验证真实 Worker 调用了统一运行层，而非只新增未使用的封装。
        TaskStore tasks = mock(TaskStore.class); RedisSlots slots = mock(RedisSlots.class); Channel channel = mock(Channel.class); ObjectMapper json = new ObjectMapper(); // 只替换数据库和消息通道。
        Task task = mock(Task.class); when(task.id()).thenReturn("task"); when(task.traceId()).thenReturn("trace"); when(task.userId()).thenReturn("user"); when(task.state()).thenReturn("QUEUED"); // 创建持久化任务快照。
        Claim claim = new Claim(task, 1, "worker"); // 当前执行尝试具备有效凭证。
        when(tasks.get("task")).thenReturn(task); when(slots.acquire(eq("user"), anyString())).thenReturn(true); when(tasks.claim(eq("task"), anyString())).thenReturn(Optional.of(claim)); // 允许实际进入执行方法。
        when(tasks.readSettings(task)).thenReturn(new AgentSettings("test-model", 2048, "safe-v1")); when(tasks.readInput(task)).thenReturn(new Submit("验证运行入口", null, 3)); // 使用数据库中的固定配置。
        when(tasks.heartbeat(claim)).thenReturn(true); when(slots.refresh(eq("user"), anyString())).thenReturn(true); when(tasks.finish(eq(claim), any())).thenReturn(true); // 执行权在本测试内保持有效。
        TaskConsumer consumer = new TaskConsumer(tasks, slots, mock(KnowledgeStore.class), json); ReflectionTestUtils.setField(consumer, "maxRuntime", 30); // 不依赖 Spring 注入，也不调用真实模型。
        try { // 消费后应在 ACK 前完成正式结果结算。
            consumer.consume(message(json.writeValueAsBytes(new Dispatch(1, "task", "trace"))), channel); // 真正走完模型演示、Harness 验收和 Worker 结算。
            ArgumentCaptor<Result> result = ArgumentCaptor.forClass(Result.class); verify(tasks).finish(eq(claim), result.capture()); // 捕获实际结算参数。
            assertTrue(result.getValue().success()); assertTrue(result.getValue().answer().contains("验证运行入口")); // 保存验收后的演示答案。
            verify(tasks, times(2)).append(eq(claim), eq("harness"), anyString()); // 同一尝试记录运行边界和最终验收阶段。
            var order = inOrder(tasks, channel); order.verify(tasks).finish(eq(claim), any()); order.verify(channel).basicAck(7, false); // 结算仍先于消息确认。
            verify(slots).release(eq("user"), anyString()); // 统一运行层没有改变共享容量释放责任。
        } finally { consumer.close(); } // 清理消费者的心跳执行器。
    } // 结束 Worker 与 Harness 接线回归。
} // 结束 Worker 边界测试。
