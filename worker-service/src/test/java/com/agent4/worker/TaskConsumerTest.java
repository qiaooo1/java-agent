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
import com.agent4.contract.DiagnosisPolicy; // 使用当前正式诊断工具版本。
import com.sun.net.httpserver.HttpServer; // 本机证据源覆盖实际 Worker 的恢复接线。
import java.net.InetSocketAddress; // 不占用固定端口或公网地址。
import java.nio.charset.StandardCharsets; // 测试响应以 UTF-8 编码。
import java.util.Map; // 构造安全合成证据。
import java.util.concurrent.atomic.AtomicInteger; // HTTP 线程与测试线程共享请求计数。
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
        when(tasks.get("task")).thenReturn(task); when(tasks.readyForExecution("task")).thenReturn(true); when(slots.tryAcquire(eq("user"), anyString())).thenReturn(RedisSlots.Admission.ACQUIRED); when(tasks.claim(eq("task"), anyString())).thenReturn(Optional.empty()); // 模拟另一个 Worker 抢先成功。
        TaskConsumer consumer = new TaskConsumer(tasks, slots, mock(KnowledgeStore.class), json); // 构造真实消费者。
        try { consumer.consume(message(json.writeValueAsBytes(new Dispatch(1, "task", "trace"))), channel); verify(channel).basicAck(7, false); verify(slots).release(eq("user"), anyString()); } finally { consumer.close(); } // 重复消息 ACK 并精确归还自己的容量。
    } // 结束重复消费测试。
    @Test void claimedTaskRunsThroughHarnessBeforeSettlement() throws Exception { // 验证真实 Worker 调用了统一运行层，而非只新增未使用的封装。
        TaskStore tasks = mock(TaskStore.class); RedisSlots slots = mock(RedisSlots.class); Channel channel = mock(Channel.class); ObjectMapper json = new ObjectMapper(); // 只替换数据库和消息通道。
        Task task = mock(Task.class); when(task.id()).thenReturn("task"); when(task.traceId()).thenReturn("trace"); when(task.userId()).thenReturn("user"); when(task.state()).thenReturn("QUEUED"); // 创建持久化任务快照。
        Claim claim = new Claim(task, 1, "worker"); // 当前执行尝试具备有效凭证。
        when(tasks.get("task")).thenReturn(task); when(tasks.readyForExecution("task")).thenReturn(true); when(slots.tryAcquire(eq("user"), anyString())).thenReturn(RedisSlots.Admission.ACQUIRED); when(tasks.claim(eq("task"), anyString())).thenReturn(Optional.of(claim)); // 允许实际进入执行方法。
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
    @Test void recoveredDiagnosisPersistsAttemptAndRetryEventsBeforeAcknowledging() throws Exception { // 验证恢复不是只在核心单元测试中生效。
        AtomicInteger requests = new AtomicInteger(); HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); ObjectMapper json = new ObjectMapper(); // 仅启动本机临时来源。
        source.createContext("/v1/", exchange -> { // 第一次读取失败，随后提供足够完成演示调查的证据。
            if (requests.incrementAndGet() == 1) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; } // 第一次失败不得产生证据。
            String kind = exchange.getRequestURI().getPath().split("/")[3]; // 工具使用固定的演示环境。
            Object data = kind.equals("metrics") ? Map.of("consumers", 0, "brokerConnected", true, "slotsUsed", 0, "slotsLimit", 8, "incomplete", false) : Map.of("message", "bounded observation"); // 合成现场不是生产结果。
            byte[] body = json.writeValueAsBytes(Map.of("environment", "demo-a", "synthetic", true, "data", data)); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); // 真实工具负责解析并记录引用。
        }); source.start(); // 准备 Worker 可访问的证据端点。
        TaskStore tasks = mock(TaskStore.class); RedisSlots slots = mock(RedisSlots.class); Channel channel = mock(Channel.class); Task task = mock(Task.class); // 替换持久化和消息边界。
        when(task.id()).thenReturn("incident"); when(task.userId()).thenReturn("user"); when(task.traceId()).thenReturn("trace"); when(task.state()).thenReturn("QUEUED"); Claim claim = new Claim(task, 1, "worker"); // 固定本次执行凭证。
        when(tasks.get("incident")).thenReturn(task); when(tasks.readyForExecution("incident")).thenReturn(true); when(tasks.claim(eq("incident"), anyString())).thenReturn(Optional.of(claim)); when(slots.tryAcquire(eq("user"), anyString())).thenReturn(RedisSlots.Admission.ACQUIRED); // 允许正式领取。
        when(tasks.readSettings(task)).thenReturn(new AgentSettings("test-model", 2048, DiagnosisPolicy.VERSION)); when(tasks.readInput(task)).thenReturn(new Submit("调查任务排队", null, 8, new DiagnosisScope("demo-workbench", "demo-a", "2026-11-05T01:00:00Z", "2026-11-05T01:15:00Z"))); // 使用服务端范围和配置。
        when(tasks.heartbeat(claim)).thenReturn(true); when(slots.refresh(eq("user"), anyString())).thenReturn(true); when(tasks.finish(eq(claim), any())).thenReturn(true); // 执行权始终有效。
        TaskConsumer consumer = new TaskConsumer(tasks, slots, mock(KnowledgeStore.class), json); ReflectionTestUtils.setField(consumer, "maxRuntime", 30); ReflectionTestUtils.setField(consumer, "diagnosisSource", "http://127.0.0.1:" + source.getAddress().getPort()); // 使用规则演示客户端，不连接真实供应商。
        try { // 故障和恢复均经过实际 TaskConsumer.execute。
            consumer.consume(message(json.writeValueAsBytes(new Dispatch(1, "incident", "trace"))), channel); // 包括安全重试、报告验收与结果结算。
            ArgumentCaptor<Result> result = ArgumentCaptor.forClass(Result.class); verify(tasks).finish(eq(claim), result.capture()); assertTrue(result.getValue().success()); assertEquals(4, json.readTree(result.getValue().answer()).path("evidence").size()); assertEquals(5, requests.get()); // 失败尝试没有变成重复证据。
            ArgumentCaptor<String> retry = ArgumentCaptor.forClass(String.class); verify(tasks).append(eq(claim), eq("tool_retry"), retry.capture()); assertEquals("TRANSIENT_FAILURE", json.readTree(retry.getValue()).path("reason").asText()); assertEquals(2, json.readTree(retry.getValue()).path("nextAttempt").asInt()); // 持久化真实恢复安排。
            verify(tasks, times(5)).append(eq(claim), eq("tool_attempt"), anyString()); // 四次成功与一次失败都留下安全状态。
            ArgumentCaptor<String> steps = ArgumentCaptor.forClass(String.class); verify(tasks, atLeastOnce()).append(eq(claim), eq("step"), steps.capture()); // 进度事件来自实际 Worker 回调。
            assertTrue(steps.getAllValues().stream().anyMatch(value -> value.contains("RETRYING"))); assertTrue(steps.getAllValues().get(steps.getAllValues().size() - 1).contains("VALIDATION")); assertTrue(steps.getAllValues().get(steps.getAllValues().size() - 1).contains("SUCCEEDED")); // 重试可见，正式验收结束可见。
            var order = inOrder(tasks, channel); order.verify(tasks).finish(eq(claim), any()); order.verify(channel).basicAck(7, false); verify(slots).release(eq("user"), anyString()); // 原有消息确认和容量释放不被改变。
        } finally { consumer.close(); source.stop(0); } // 清理心跳与本机 HTTP 服务。
    } // 结束 Worker 实际恢复接线场景。
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = RedisSlots.Admission.class, names = {"USER_LIMIT", "PROVIDER_LIMIT"})
    void capacityDenialPersistsWaitBeforeAckAndDoesNotClaim(RedisSlots.Admission reason) throws Exception {
        TaskStore tasks = mock(TaskStore.class); RedisSlots slots = mock(RedisSlots.class); Channel channel = mock(Channel.class); ObjectMapper json = new ObjectMapper();
        Task task = mock(Task.class); when(task.id()).thenReturn("waiting"); when(task.userId()).thenReturn("user"); when(task.state()).thenReturn("QUEUED");
        when(tasks.get("waiting")).thenReturn(task); when(tasks.readyForExecution("waiting")).thenReturn(true);
        when(slots.tryAcquire(eq("user"), anyString())).thenReturn(reason);
        TaskConsumer consumer = new TaskConsumer(tasks, slots, mock(KnowledgeStore.class), json);
        try {
            consumer.consume(message(json.writeValueAsBytes(new Dispatch(1, "waiting", "trace"))), channel);
            var order = inOrder(tasks, channel); order.verify(tasks).defer("waiting", reason.name()); order.verify(channel).basicAck(7, false);
            verify(tasks, never()).claim(anyString(), anyString()); verify(slots, never()).release(anyString(), anyString());
        } finally { consumer.close(); }
    }
    @Test void earlyNotificationDoesNotOccupyRedisOrCreateExecutionResources() throws Exception {
        TaskStore tasks = mock(TaskStore.class); RedisSlots slots = mock(RedisSlots.class); Channel channel = mock(Channel.class); ObjectMapper json = new ObjectMapper();
        Task task = mock(Task.class); when(task.state()).thenReturn("RETRY_WAIT"); when(task.id()).thenReturn("early"); when(tasks.get("early")).thenReturn(task);
        TaskConsumer consumer = new TaskConsumer(tasks, slots, mock(KnowledgeStore.class), json);
        try {
            consumer.consume(message(json.writeValueAsBytes(new Dispatch(1, "early", "trace"))), channel);
            verify(channel).basicAck(7, false); verifyNoInteractions(slots); verify(tasks, never()).claim(anyString(), anyString());
        } finally { consumer.close(); }
    }
    @Test void uncertainAcquireResponseCleansItsTokenAndRequeuesWithoutClaim() throws Exception {
        TaskStore tasks = mock(TaskStore.class); RedisSlots slots = mock(RedisSlots.class); Channel channel = mock(Channel.class); ObjectMapper json = new ObjectMapper();
        Task task = mock(Task.class); when(task.id()).thenReturn("uncertain"); when(task.userId()).thenReturn("user"); when(task.state()).thenReturn("QUEUED");
        when(tasks.get("uncertain")).thenReturn(task); when(tasks.readyForExecution("uncertain")).thenReturn(true);
        when(slots.tryAcquire(eq("user"), anyString())).thenThrow(new IllegalStateException("simulated lost response"));
        TaskConsumer consumer = new TaskConsumer(tasks, slots, mock(KnowledgeStore.class), json);
        try {
            consumer.consume(message(json.writeValueAsBytes(new Dispatch(1, "uncertain", "trace"))), channel);
            ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class); verify(slots).tryAcquire(eq("user"), token.capture()); verify(slots).release("user", token.getValue());
            verify(channel).basicNack(7, false, true); verify(channel, never()).basicAck(anyLong(), anyBoolean()); verify(tasks, never()).claim(anyString(), anyString());
        } finally { consumer.close(); }
    }
    @Test void failedDurableDeferralMustNotAcknowledgeNotification() throws Exception {
        TaskStore tasks = mock(TaskStore.class); RedisSlots slots = mock(RedisSlots.class); Channel channel = mock(Channel.class); ObjectMapper json = new ObjectMapper();
        Task task = mock(Task.class); when(task.id()).thenReturn("failed-wait"); when(task.userId()).thenReturn("user"); when(task.state()).thenReturn("QUEUED");
        when(tasks.get("failed-wait")).thenReturn(task); when(tasks.readyForExecution("failed-wait")).thenReturn(true);
        when(slots.tryAcquire(eq("user"), anyString())).thenReturn(RedisSlots.Admission.USER_LIMIT);
        when(tasks.defer("failed-wait", "USER_LIMIT")).thenThrow(new IllegalStateException("simulated commit failure"));
        TaskConsumer consumer = new TaskConsumer(tasks, slots, mock(KnowledgeStore.class), json);
        try {
            consumer.consume(message(json.writeValueAsBytes(new Dispatch(1, "failed-wait", "trace"))), channel);
            verify(channel).basicNack(7, false, true); verify(channel, never()).basicAck(anyLong(), anyBoolean()); verify(tasks, never()).claim(anyString(), anyString());
        } finally { consumer.close(); }
    }

} // 结束 Worker 边界测试。
