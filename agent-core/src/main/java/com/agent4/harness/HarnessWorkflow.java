package com.agent4.harness; // 业务只提供边界和验收规则，执行循环归 Harness 管理。
public interface HarnessWorkflow { // 诊断与通用任务通过同一扩展点接入。
    HarnessPolicy policy(); // 定义当前业务版本、工具授权与执行预算。
    default String systemPrompt() { return null; } // 空值使用 ReAct 默认提示，业务可提供自己的固定指令。
    default String accept(String answer) { return answer; } // 可以拒绝不合规输出，也可以转换为正式业务结果。
} // 结束业务策略接口。
