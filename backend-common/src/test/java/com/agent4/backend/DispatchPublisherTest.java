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
    @Test void alreadyInterruptedPublisherDoesNotStartNewWork() { // 停止信号已到达时，不应开始扫描或发送新消息。
        OutboxStore store = mock(OutboxStore.class); RabbitTemplate rabbit = mock(RabbitTemplate.class); // 观察发布器是否触发新的外部工作。
        try { // 测试结束必须清除本测试设置的中断标记。
            Thread.currentThread().interrupt(); // 模拟调度线程在发布开始前已收到停止信号。
            new DispatchPublisher(store, mock(TaskStore.class), rabbit, new ObjectMapper()).publish(); // 调用真实发布入口。
            assertTrue(Thread.currentThread().isInterrupted()); // 中断不能被吞掉或静默清除。
            verifyNoInteractions(store, rabbit); // 既不扫描数据库，也不发送消息。
        } finally { Thread.interrupted(); } // 避免污染同一 JUnit 工作线程的其他测试。
    } // 结束发布前中断测试。
    @Test void interruptedConfirmationSchedulesRetryAndStopsRemainingBatch() { // 等待 broker 确认时的中断必须终止后续领取。
        OutboxStore store = mock(OutboxStore.class); RabbitTemplate rabbit = mock(RabbitTemplate.class); // 无需真实 broker 或线程休眠。
        Outbox first = new Outbox("first", "task-1", "trace-1", "owner-1"), second = new Outbox("second", "task-2", "trace-2", "owner-2"); // 两条候选消息暴露批次继续执行的问题。
        when(store.ready()).thenReturn(List.of("first", "second")); when(store.claim("first")).thenReturn(Optional.of(first)); when(store.claim("second")).thenReturn(Optional.of(second)); // 每条消息均可被领取。
        doAnswer(invocation -> { Thread.currentThread().interrupt(); return null; }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class)); // 在进入确认等待前确定性触发中断，future 保持未完成。
        try { // 发布器应恢复 InterruptedException 清除的中断标记。
            new DispatchPublisher(store, mock(TaskStore.class), rabbit, new ObjectMapper()).publish(); // 执行当前失败处理和剩余批次控制。
            assertTrue(Thread.currentThread().isInterrupted()); // 调用方仍可检测到停止信号。
            verify(store).failed(eq(first), any(InterruptedException.class)); verify(store, never()).sent(any()); // 当前消息保留重试机会，不能标记成功。
            verify(store, never()).claim("second"); // 停止后不抢占后续消息的发布租约。
            verify(rabbit, times(1)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class)); // 只发送已经开始处理的第一条。
        } finally { Thread.interrupted(); } // 无论断言成功或失败都清理测试线程状态。
    } // 结束确认期间中断测试。
    @Test void brokerRejectionDoesNotStopOtherMessages() { // 普通投递失败不是线程停止信号，后续消息仍应有机会发送。
        OutboxStore store = mock(OutboxStore.class); RabbitTemplate rabbit = mock(RabbitTemplate.class); // 只替换外部协作边界。
        Outbox first = new Outbox("first", "task-1", "trace-1", "owner-1"), second = new Outbox("second", "task-2", "trace-2", "owner-2"); // 同一批中的两条独立任务。
        when(store.ready()).thenReturn(List.of("first", "second")); when(store.claim("first")).thenReturn(Optional.of(first)); when(store.claim("second")).thenReturn(Optional.of(second)); // 设置可领取批次。
        doAnswer(invocation -> { // 第一条返回 NACK，第二条正常确认。
            CorrelationData correlation = invocation.getArgument(3); boolean acknowledged = "second".equals(correlation.getId()); // 确认结果绑定到实际发送的消息。
            correlation.getFuture().complete(new CorrelationData.Confirm(acknowledged, acknowledged ? null : "rejected")); return null; // 不使用超时或真实网络模拟失败。
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class)); // 保留正式发送与等待控制流。
        new DispatchPublisher(store, mock(TaskStore.class), rabbit, new ObjectMapper()).publish(); // 普通失败后继续批次。
        verify(store).failed(eq(first), any(Exception.class)); verify(store).sent(second); // 首条重试与次条成功互不影响。
        assertFalse(Thread.currentThread().isInterrupted()); // broker 拒绝不会伪造线程中断。
    } // 结束普通失败与中断的边界测试。
} // 结束 Outbox 发布回归测试。
