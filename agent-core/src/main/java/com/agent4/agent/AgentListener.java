package com.agent4.agent; // 事件回调与引擎同包，核心循环通过接口解耦展示层。

import com.agent4.llm.ToolCall; // 只依赖 ToolCall 数据对象，回调侧不必反向依赖引擎实现。
import com.agent4.tool.ToolResult; // 每次实际执行尝试有独立的结构化结果。
import com.agent4.tool.ToolErrorCode; // 重试事件不包含底层异常原文。

/**
 * Agent 执行过程的事件回调。UI 层实现此接口观察每一步，
 * 核心循环不依赖任何展示细节（观察者模式）。
 *
 * <p>所有方法都有 default 空实现，只需覆写关心的事件。</p>
 */
public interface AgentListener { // 所有方法都是 default 空实现，新增事件不会破坏已有实现类。
    default void onToolAttempt(ToolCall call, int attempt, ToolResult result) {} // 一次模型调用可包含多次受控物理尝试。
    default void onToolRetry(ToolCall call, int nextAttempt, ToolErrorCode reason, long delayMillis) {} // 先记录真实重试安排，再等待并重新检查执行权。

    /** 一轮 ReAct 迭代开始。 */
    default void onIterationStart(int step) { // 每轮迭代开始回调，step 从 1 递增，UI 可据此显示进度。
    } // 空实现：未覆写时保持静默，核心循环无需关心展示细节。

    /** 模型文本增量（流式思考/回答）。 */
    default void onAssistantDelta(String delta) { // 流式文本增量，逐块推送以降低首字延迟。
    } // 空实现：不关心增量时无需处理。

    /** 模型发起工具调用。 */
    default void onActionStart(ToolCall call) { // 模型决定调用工具时触发，此时工具尚未真正执行。
    } // 空实现：调用方可以只关心最终结果。

    /** 工具执行完毕，返回 Observation。 */
    default void onObservation(ToolCall call, String observation) { // 工具执行结束回调，observation 已是给模型看的最终文本，失败也走这里。
    } // 空实现：不展示中间观察时保持静默。

    /** 最终答案。 */
    default void onFinalAnswer(String answer) { // 仅在正常收敛时触发；熔断与异常都走 onError。
    } // 空实现：非 UI 场景可忽略。

    /** 中止或异常。 */
    default void onError(String message) { // 中止或异常统一回调，参数是可读原因而非堆栈。
    } // 空实现：默认不感知失败细节。
} // 结束接口声明。
