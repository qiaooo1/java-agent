package com.agent4.agent; // 轨迹与引擎同包，便于引擎以包内方法推进状态。

import com.agent4.llm.ChatResponse; // 只用到响应里的 Usage，避免轨迹依赖整个响应体。
import com.agent4.util.Json; // 复用全局序列化门面，保证落盘格式与接口返回一致。

import java.util.ArrayList; // 步骤数量与迭代上限同量级，ArrayList 足够。
import java.util.List; // 对外只返回副本，内部用可变列表累积。

/**
 * 一次完整任务的执行轨迹，运行结束后落盘，用于复盘。
 */
public final class AgentTrace { // final 类：轨迹构造后只允许包内方法推进，外部不能改写历史。

    public enum Status { RUNNING, COMPLETED, ABORTED, ERROR } // 状态机取值，落盘后可区分正常完成、主动中止与异常。

    /** 单轮 ReAct 迭代记录。 */
    public record StepRecord(int step, String thought, List<StepRecord.ToolStep> toolSteps) { // record 让每步记录天然不可变，序列化时无需防御性拷贝。
        public record ToolStep(String toolName, String argumentsJson, String observation) { // 工具子步骤原样保留参数与观察结果，失败文本也不丢。
        } // 结束嵌套 ToolStep 声明。
    } // 结束 StepRecord 声明，其字段由引擎逐轮填充。

    private final String userGoal; // 用户目标在构造时固定，复盘时可定位原始任务。
    private final List<StepRecord> steps = new ArrayList<>(); // 步骤只由引擎追加，外部拿到的永远是副本。
    private Status status = Status.RUNNING; // 初始为 RUNNING，任何出口都必须推进到终态。
    private String finalAnswer; // 仅在 COMPLETED 时写入最终答案。
    private String error; // 中止或异常原因，前端可直接展示。
    private int promptTokens; // 多次迭代累加的提示词侧消耗。
    private int completionTokens; // 与提示词分开累计，便于分析成本结构。
    private long durationMs; // 总耗时在收尾时一次性写入。

    public AgentTrace(String userGoal) { // 构造只接收目标，其余字段依赖默认初值。
        this.userGoal = userGoal; // 保存目标而非从首条消息推断，语义更直接。
    } // 构造结束，轨迹进入 RUNNING 状态。

    void addStep(StepRecord step) { // 包内可见：只有引擎能追加步骤，防止外部伪造轨迹。
        steps.add(step); // 按执行顺序追加，不做重排。
    } // 结束 addStep。

    void addUsage(ChatResponse.Usage usage) { // 用量可能为空，空值时不参与累计。
        if (usage != null) { // 空用量说明客户端未返回统计，跳过以免空指针。
            this.promptTokens += usage.promptTokens(); // 跨迭代累加提示词侧消耗。
            this.completionTokens += usage.completionTokens(); // 跨迭代累加补全侧消耗。
        } // 结束空值保护分支。
    } // 结束 addUsage。

    void complete(String answer) { // 正常收敛：推进到 COMPLETED 并记录最终答案。
        this.status = Status.COMPLETED; // 状态先落定，后续读取不会再看到运行中。
        this.finalAnswer = answer; // 最终答案同时用于展示与落盘。
    } // 结束 complete。

    void abort(String reason) { // 可控中止：标记 ABORTED，原因以可读文本保存。
        this.status = Status.ABORTED; // 与异常路径区分，便于统计熔断发生率。
        this.error = reason; // 中止原因写入 error 字段，与异常共用同一展示位。
    } // 结束 abort。

    void fail(Throwable t) { // 异常路径：记录类型与消息，不保留堆栈以免轨迹过大。
        this.status = Status.ERROR; // 归一为 ERROR，调用方据此决定是否告警。
        this.error = t.getClass().getSimpleName() + ": " + t.getMessage(); // 把异常类型与消息拼成可读文本，便于复盘。
    } // 结束 fail。

    void finishAt(long durationMs) { // 耗时在收尾时统一写入，保证所有出口都能带到。
        this.durationMs = durationMs; // 只存总时长，不记录分段耗时以保持轨迹轻量。
    } // 结束 finishAt。

    public String userGoal() { // 以下访问器均为只读，序列化后不会暴露可变内部结构。
        return userGoal; // 返回构造时固定的用户目标。
    } // 结束 userGoal。

    public Status status() { // 当前状态，落盘后用于区分结果性质。
        return status; // 返回状态字段本身，不做额外转换。
    } // 结束 status。

    public String finalAnswer() { // 只有正常完成时才有值，其余情况为 null。
        return finalAnswer; // 返回最终答案原文，不做截断。
    } // 结束 finalAnswer。

    public String error() { // null 表示未发生中止或异常。
        return error; // 返回可读的失败原因。
    } // 结束 error。

    public List<StepRecord> steps() { // 返回内部列表本身，供调用方合并或统计。
        return steps; // 列表元素不可变，暴露引用不会破坏历史。
    } // 结束 steps。

    public int promptTokens() { // 提示词侧累计用量。
        return promptTokens; // 返回累计值，单位与模型返回一致。
    } // 结束 promptTokens。

    public int completionTokens() { // 补全侧累计用量。
        return completionTokens; // 与提示词分开返回，便于分别计费。
    } // 结束 completionTokens。

    public long durationMs() { // 总耗时，仅在收尾后有效。
        return durationMs; // 返回毫秒数，未收尾时为 0。
    } // 结束 durationMs。

    public String toJson() { // 轨迹序列化复用一个 ObjectMapper，落盘字段顺序稳定。
        return Json.write(this); // 直接写自身字段，无需额外 DTO。
    } // 结束 toJson。
} // 结束 AgentTrace，实例由引擎单独持有并最终返回。
