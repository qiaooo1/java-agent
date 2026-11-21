package com.agent4.worker; // 只记录实际执行步骤，不预设模型会选择什么工具。
import com.agent4.contract.Contracts.ExecutionStep; // 每次状态更新保存完整可回放快照。
import com.agent4.tool.ToolResult; // 以结构化分类展示恢复过程。
import com.agent4.tool.ToolErrorCode; // 失败提示来自受控常量。
import java.time.Clock; // 可注入时钟验证耗时，无需休眠。
import java.util.function.Consumer; // 持久化由外部带 fencing 的事件出口负责。
public final class ExecutionProgress { // 每个 Worker 执行尝试独立持有。
    private final int attempt; private final Consumer<ExecutionStep> sink; private final Clock clock; private int position; // 序号按实际开始顺序递增。
    private ExecutionStep model, tool, validation; // 当前步骤的完整状态。
    public ExecutionProgress(int attempt, Consumer<ExecutionStep> sink, Clock clock) { this.attempt = attempt; this.sink = sink; this.clock = clock; } // 不持有数据库连接或线程。
    private ExecutionStep start(String kind, String title, int iteration, String name) { // 仅在对应工作真正开始前创建步骤。
        long now = clock.millis(); int order = ++position; // 时间和顺序分别记录，避免同毫秒排序混乱。
        ExecutionStep step = new ExecutionStep("a" + attempt + "-step-" + order, attempt, order, kind, title, "RUNNING", iteration, name, 0, "", "执行中", now, now, 0); sink.accept(step); return step; // 第一次事件也是完整快照。
    } // 结束步骤创建。
    private ExecutionStep update(ExecutionStep step, String status, int toolAttempt, String error, String detail) { // 同一个步骤状态变化不创建重复条目。
        long now = Math.max(step.startedAtMillis(), clock.millis()); // 处理宿主时钟轻微回拨。
        ExecutionStep next = new ExecutionStep(step.id(), attempt, step.position(), step.kind(), step.title(), status, step.iteration(), step.tool(), toolAttempt, error, detail, step.startedAtMillis(), now, now - step.startedAtMillis()); sink.accept(next); return next; // 耗时基于实际开始时刻。
    } // 结束状态更新。
    public void modelStarted(int iteration) { model = start("MODEL", "第 " + iteration + " 轮模型分析", iteration, ""); } // 不记录模型隐性思考内容。
    public void modelCompleted(int iteration) { if (model != null && model.iteration() == iteration) model = update(model, "SUCCEEDED", 0, "", "已收到模型响应"); } // 完成表示响应已到达，不代表任务成功。
    public void toolStarted(String name) { tool = start("TOOL", "调用 " + name, model == null ? 0 : model.iteration(), name); } // 工具名称来自服务端注册表。
    public void toolAttempt(int number, ToolResult result) { // 自动重试属于同一逻辑工具步骤。
        if (tool == null) return; // 保持事件监听器的向后兼容。
        String status = result.successful() ? "SUCCEEDED" : result.error() == ToolErrorCode.TRANSIENT_FAILURE && number == 1 ? "RUNNING" : "FAILED"; // 临时失败先等待恢复决策。
        tool = update(tool, status, number, result.successful() ? "" : result.error().name(), result.successful() ? "第 " + number + " 次调用成功" : "第 " + number + " 次调用失败：" + result.error().message()); // 不回显原始异常。
    } // 结束实际尝试更新。
    public void retry(int next, ToolErrorCode reason, long delay) { if (tool != null) tool = update(tool, "RETRYING", tool.toolAttempt(), reason.name(), "等待 " + delay + " 毫秒后进行第 " + next + " 次尝试"); } // 安排重试不等于第二次已经执行。
    public void validationStarted() { validation = start("VALIDATION", "验收正式结果", model == null ? 0 : model.iteration(), ""); } // 真正进入 accept 时才创建。
    private boolean active(ExecutionStep step) { return step != null && (step.status().equals("RUNNING") || step.status().equals("RETRYING")); } // 已结束的步骤不能被后续结果重写。
    public void finish(boolean success) { // 验收结束后补齐本次可确认的状态。
        if (active(model)) model = update(model, "INTERRUPTED", 0, "", "未收到本轮完成事件，执行已结束"); // 不推测被中断的模型请求成功。
        if (active(tool)) tool = update(tool, success ? "INTERRUPTED" : "FAILED", tool.toolAttempt(), tool.errorCode(), "本次执行已结束，步骤未完成"); // 保留已完成的失败与恢复历史。
        if (active(validation)) validation = update(validation, success ? "SUCCEEDED" : "FAILED", 0, "", success ? "正式结果验收通过" : "结果未通过验收或执行已停止"); // 只依据 Harness 的正式结果。
    } // 结束运行进度记录。
} // 结束单次尝试的步骤记录器。
