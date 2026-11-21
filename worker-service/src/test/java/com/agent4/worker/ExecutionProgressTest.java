package com.agent4.worker; // 验证可展示步骤只来自真实回调。
import com.agent4.contract.Contracts.ExecutionStep; // 与持久化进度接口使用同一协议。
import com.agent4.tool.*; // 复用结构化工具错误。
import org.junit.jupiter.api.Test; // 顺序、恢复和中断场景。
import java.time.*; // 可控时间无需休眠。
import java.util.*; // 保存实际发出的更新。
import static org.junit.jupiter.api.Assertions.*; // 验证顺序、身份和阶段完成依据。
class ExecutionProgressTest { // 不模拟数据库或 UI 实现细节。
    static final class Time extends Clock { long now = 1000; public Instant instant() { return Instant.ofEpochMilli(now); } public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; } } // 固定耗时场景。
    @Test void retryUpdatesOneLogicalStepAndKeepsRealOrder() { // 工具内部重试不能冒充一个新的业务步骤。
        List<ExecutionStep> updates = new ArrayList<>(); Time clock = new Time(); ExecutionProgress progress = new ExecutionProgress(2, updates::add, clock); // 第二个执行尝试有独立身份。
        progress.modelStarted(1); clock.now += 200; progress.modelCompleted(1); progress.toolStarted("read_health"); progress.toolAttempt(1, ToolResult.failure(ToolErrorCode.TRANSIENT_FAILURE)); progress.retry(2, ToolErrorCode.TRANSIENT_FAILURE, 100); clock.now += 100; progress.toolAttempt(2, ToolResult.success("evidence")); progress.validationStarted(); clock.now += 50; progress.finish(true); // 实际顺序和时间。
        List<ExecutionStep> tool = updates.stream().filter(step -> step.kind().equals("TOOL")).toList(); assertEquals(1, tool.stream().map(ExecutionStep::id).distinct().count()); assertTrue(tool.stream().anyMatch(step -> step.status().equals("RETRYING"))); assertEquals("SUCCEEDED", tool.get(tool.size() - 1).status()); assertEquals(100, tool.get(tool.size() - 1).elapsedMillis()); // 失败后恢复仍是同一个工具步骤。
        assertEquals(1, tool.stream().filter(step -> step.status().equals("RETRYING")).findFirst().orElseThrow().toolAttempt());
        ExecutionStep last = updates.get(updates.size() - 1); assertEquals("VALIDATION", last.kind()); assertEquals("SUCCEEDED", last.status()); assertEquals(3, last.position()); assertTrue(updates.stream().allMatch(step -> step.attemptNo() == 2)); // 不预设未来工具顺序。
    } // 结束恢复进度测试。
    @Test void stoppedExecutionDoesNotInventModelSuccess() { // 未收到响应时不能声称模型分析完成。
        List<ExecutionStep> updates = new ArrayList<>(); ExecutionProgress progress = new ExecutionProgress(1, updates::add, new Time()); progress.modelStarted(1); progress.finish(false); // 模拟模型阶段失联或异常。
        assertEquals("INTERRUPTED", updates.get(updates.size() - 1).status()); assertTrue(updates.stream().noneMatch(step -> step.kind().equals("VALIDATION"))); // 没有进入验收就不显示验收步骤。
    } // 结束中断阶段测试。
    @Test void finalFailureDoesNotOverwriteAlreadyFailedTool() { // 历史失败保持原有原因。
        List<ExecutionStep> updates = new ArrayList<>(); ExecutionProgress progress = new ExecutionProgress(1, updates::add, new Time()); progress.modelStarted(1); progress.modelCompleted(1); progress.toolStarted("read_logs"); progress.toolAttempt(1, ToolResult.failure(ToolErrorCode.PERMISSION_DENIED)); int count = updates.size(); progress.finish(false); // 权限失败已经明确结束。
        assertEquals(count, updates.size()); assertEquals("PERMISSION_DENIED", updates.get(count - 1).errorCode()); assertEquals("FAILED", updates.get(count - 1).status()); // 不以模糊的运行结束覆盖实际失败。
    } // 结束失败状态稳定性测试。
} // 结束 Worker 步骤记录器回归。
