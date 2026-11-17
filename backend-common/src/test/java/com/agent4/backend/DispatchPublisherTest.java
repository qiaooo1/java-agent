package com.agent4.backend; // 模拟 broker 确认故障，验证发布器不会提前标记成功。
import com.agent4.contract.Contracts.Outbox; // 发布凭证带唯一持有者。
import com.fasterxml.jackson.databind.ObjectMapper; // 使用真正 JSON 编码。
import org.springframework.amqp.rabbit.core.RabbitTemplate; // Mock 只替换外部消息服务。
import org.springframework.amqp.rabbit.connection.CorrelationData; // 手动控制确认结果。
import org.springframework.amqp.core.Message; // 检查消息体与属性。
import org.junit.jupiter.api.Test; // 独立发布器回归场景。
import static org.mockito.Mockito.*; // 模拟不可达服务与 broker 拒绝。
import static org.junit.jupiter.api.Assertions.*; // 验证持久化和追踪属性。
import java.util.*; // 有限待发批次。
class DispatchPublisherTest { // 不依赖正在运行的 RabbitMQ。
    @Test void confirmationSuccessMarksSent() throws Exception { // 只有 broker ACK 后才能完成 Outbox。
        OutboxStore store = mock(OutboxStore.class); RabbitTemplate rabbit = mock(RabbitTemplate.class); // 替换网络与发布状态边界。
        Outbox event = new Outbox("e", "task", "trace", "owner"); // 固定消息标识便于断言。
        when(store.ready()).thenReturn(List.of("e")); when(store.claim("e")).thenReturn(Optional.of(event)); // 仅一条消息成功领取。
        doAnswer(invocation -> { // 在发送时模拟 broker ACK。
            Message message = invocation.getArgument(2); CorrelationData correlation = invocation.getArgument(3); // 获取实际发送的属性和确认凭证。
            assertEquals("trace", message.getMessageProperties().getHeader("traceId")); // 跨服务追踪不能丢失。
            assertEquals(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode()); // 消息必须持久化。
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null)); return null; // 完成关联发布确认。
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class)); // 不执行真实网络 IO。
        new DispatchPublisher(store, mock(TaskStore.class), rabbit, new ObjectMapper()).publish(); // 执行正式发布逻辑。
        verify(store).sent(event); verify(store, never()).failed(any(), any()); // 成功路径不可错误安排失败。
    } // 结束确认成功测试。
    @Test void brokerRejectionSchedulesRetry() { // broker NACK 不等同于可靠投递成功。
        OutboxStore store = mock(OutboxStore.class); RabbitTemplate rabbit = mock(RabbitTemplate.class); // 保留正式发布控制流。
        Outbox event = new Outbox("e", "task", "trace", "owner"); // 创建本次发布凭证。
        when(store.ready()).thenReturn(List.of("e")); when(store.claim("e")).thenReturn(Optional.of(event)); // 设置待发记录。
        doAnswer(invocation -> { CorrelationData correlation = invocation.getArgument(3); correlation.getFuture().complete(new CorrelationData.Confirm(false, "rejected")); return null; }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class)); // 显式模拟拒绝。
        new DispatchPublisher(store, mock(TaskStore.class), rabbit, new ObjectMapper()).publish(); // 执行失败路径。
        verify(store, never()).sent(any()); verify(store).failed(eq(event), any(Exception.class)); // 必须保留重试记录。
    } // 结束 broker 拒绝测试。
} // 结束 Outbox 发布回归测试。
