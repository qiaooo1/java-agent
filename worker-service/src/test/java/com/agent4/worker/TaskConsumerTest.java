package com.agent4.worker; // 验证消息边界，不把永久错误与暂时错误混在一起。
import com.agent4.backend.*; // 替换数据库和 Redis，不调用真实模型。
import com.agent4.contract.Contracts.*; // 使用正式消息与任务协议。
import com.fasterxml.jackson.databind.ObjectMapper; // 真实序列化版本化消息。
import com.rabbitmq.client.Channel; // 检查 ACK、NACK 和死信选择。
import org.springframework.amqp.core.*; // 创建接收消息。
import org.junit.jupiter.api.Test; // Worker 协议回归测试。
import static org.mockito.Mockito.*; // 只 Mock 外部设施与数据库边界。
import java.util.Optional; // 模拟领取竞争失败。
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
} // 结束 Worker 边界测试。
