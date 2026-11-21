package com.agent4.tool; // 验证单次执行的分类与安全返回，不连接外部系统。
import org.junit.jupiter.api.Test; // 明确的失败类型场景。
import org.junit.jupiter.params.ParameterizedTest; // 不同坏参数共用同一契约。
import org.junit.jupiter.params.provider.ValueSource; // 固定 JSON 样例。
import java.io.IOException; // 模拟可能的传输故障。
import java.util.concurrent.CountDownLatch; // 确定性阻塞工具，不依赖长时间休眠。
import java.util.concurrent.atomic.AtomicInteger; // 观察真实工具是否执行。
import static org.junit.jupiter.api.Assertions.*; // 校验分类、幂等声明与敏感数据边界。
class ToolErrorTest { // 原始异常文字不得成为 Observation。
    public static class Tools { // 可由正式注册表反射调用。
        final AtomicInteger calls = new AtomicInteger(); final CountDownLatch release = new CountDownLatch(1); // 保存可检查副作用。
        @Tool(description = "参数转换测试") public int number(int value) { calls.incrementAndGet(); return value; } // 转换失败时不能执行方法体。
        @Tool(description = "临时读取", retrySafe = true) public String unavailable() throws IOException { calls.incrementAndGet(); throw new IOException("password=fixture-secret; private-host"); } // 异常包含不能回显的测试标记。
        @Tool(description = "权限拒绝") public String denied() { throw new SecurityException("fixture-secret"); } // 权限错误单独分类。
        @Tool(description = "内部失败") public String broken() { throw new IllegalStateException("jdbc:fixture-secret"); } // 内部错误不展示连接信息。
        @Tool(description = "阻塞读取", retrySafe = true) public String slow() throws InterruptedException { calls.incrementAndGet(); release.await(); return "done"; } // 超时后即使声明安全也不能假设原调用已完成。
    } // 结束测试工具。
    @ParameterizedTest @ValueSource(strings = {"[]", "{}", "{\"value\":null}", "{\"value\":\"fixture-secret\"}", "{fixture-secret"}) void invalidArgumentsNeverExecuteOrEchoInput(String json) { // 缺参、类型和语法错误均可让模型纠正。
        Tools probe = new Tools(); ToolRegistry registry = new ToolRegistry().register(probe); // 实际转换仍经过异步与反射边界。
        try { ToolResult result = registry.execute("number", json, 1000); assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error()); assertEquals(0, probe.calls.get()); assertFalse(result.observation().contains("fixture-secret")); } finally { registry.closeWorkers(); } // 不泄露原始 JSON。
    } // 结束错误参数场景。
    @Test void reflectionAndAsyncWrappersPreserveFailureCategory() { // IOException 经两层包装后仍应识别为瞬时故障。
        Tools probe = new Tools(); ToolRegistry registry = new ToolRegistry().register(probe); // 不在注册表里自动重复执行。
        try { ToolResult result = registry.execute("unavailable", "{}", 1000); assertEquals(ToolErrorCode.TRANSIENT_FAILURE, result.error()); assertEquals(1, probe.calls.get()); assertTrue(registry.retrySafe("unavailable")); assertFalse(result.observation().contains("fixture-secret")); } finally { registry.closeWorkers(); } // 恢复次数必须由 Harness 预算决定。
    } // 结束异常解包场景。
    @Test void permissionAndInternalFailuresUseSafeMessages() { // 数据安全不依赖工具作者的异常消息是否干净。
        ToolRegistry registry = new ToolRegistry().register(new Tools()); // 两种失败有不同处置方式。
        try { ToolResult denied = registry.execute("denied", "{}", 1000), broken = registry.execute("broken", "{}", 1000); assertEquals(ToolErrorCode.PERMISSION_DENIED, denied.error()); assertEquals(ToolErrorCode.EXECUTION_FAILED, broken.error()); assertFalse(denied.observation().contains("fixture-secret")); assertFalse(broken.observation().contains("jdbc")); assertFalse(registry.retrySafe("broken")); } finally { registry.closeWorkers(); } // 未声明的工具默认不能重试。
    } // 结束安全分类场景。
    @Test void unknownToolDoesNotExposeNameAndLegacyInterfaceStillWorks() { // 兼容旧调用方，但不恢复前缀推断逻辑。
        ToolRegistry registry = new ToolRegistry().register(new Tools()); // 未注册名字只返回受控分类。
        try { assertEquals(ToolErrorCode.UNKNOWN_TOOL, registry.execute("fixture-secret", "{}", 1000).error()); assertFalse(registry.invoke("fixture-secret", "{}").contains("fixture-secret")); assertTrue(registry.invoke("number", "{\"value\":4}").contains("4")); } finally { registry.closeWorkers(); } // 成功输出保持原样。
    } // 结束兼容场景。
    @Test void timeoutIsExplicitAndDoesNotPretendWorkWasUndone() { // 等待超时单独分类，恢复策略必须保守。
        Tools probe = new Tools(); ToolRegistry registry = new ToolRegistry().register(probe); // 工具由 latch 阻塞。
        try { ToolResult result = registry.execute("slow", "{}", 20); assertEquals(ToolErrorCode.TIMEOUT, result.error()); assertTrue(result.error().terminal()); } finally { probe.release.countDown(); registry.closeWorkers(); } // 释放测试可能残留的工作。
    } // 结束超时场景。
    @Test void interruptedThreadDoesNotSubmitToolAndKeepsSignal() { // 取消不能启动新的工具工作。
        Tools probe = new Tools(); ToolRegistry registry = new ToolRegistry().register(probe); // 保留真实执行器。
        try { Thread.currentThread().interrupt(); assertEquals(ToolErrorCode.CANCELLED, registry.execute("number", "{\"value\":4}", 1000).error()); assertEquals(0, probe.calls.get()); assertTrue(Thread.currentThread().isInterrupted()); } finally { Thread.interrupted(); registry.closeWorkers(); } // 不污染其他测试。
    } // 结束中断场景。
    @Test void closedExecutorReturnsResourceFailure() { // 线程池拒绝不回显内部实现信息。
        ToolRegistry registry = new ToolRegistry().register(new Tools()); registry.closeWorkers(); // 确定性触发拒绝提交。
        assertEquals(ToolErrorCode.RESOURCE_EXHAUSTED, registry.execute("number", "{\"value\":4}", 1000).error()); // 无忙重试或原始异常文本。
    } // 结束资源拒绝场景。
} // 结束单次工具分类测试。
