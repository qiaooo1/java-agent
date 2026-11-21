package com.agent4.tool; // 工具成功与失败通过类型区分，不能依赖内容前缀。
import com.agent4.util.Json; // 使用既有 JSON 编码器构造结构化失败观察。
import java.util.Map; // 保持错误 JSON 字段稳定。
import java.util.Objects; // 失败必须指定分类。
public record ToolResult(String output, ToolErrorCode error) { // 失败结果不携带原始异常或参数。
    public ToolResult { if (error != null) output = null; else if (output == null) output = "ok"; } // 禁止在失败分支夹带内部异常文本。
    public static ToolResult success(String output) { return new ToolResult(output, null); } // 正常内容可以包含“错误”等业务文字。
    public static ToolResult failure(ToolErrorCode error) { return new ToolResult(null, Objects.requireNonNull(error)); } // 创建受控失败结果。
    public boolean successful() { return error == null; } // 执行器和引擎共用成功判定。
    public String observation() { return successful() ? output : Json.write(Map.of("ok", false, "error", Map.of("code", error.name(), "message", error.message(), "recovery", error.recovery()))); } // 模型只获得安全错误码与处理建议。
    public String legacyObservation() { return successful() ? output : "错误: " + observation(); } // 保持旧 invoke 字符串接口兼容，不参与内部成功判断。
} // 结束结构化工具结果。
