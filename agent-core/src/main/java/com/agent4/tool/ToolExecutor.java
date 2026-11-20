package com.agent4.tool; // 引擎面向工具能力编程，允许 Harness 在注册表外增加治理。
import com.fasterxml.jackson.databind.JsonNode; // 工具声明沿用模型接口协议。
import java.util.List; // 有序工具声明。
import java.util.Set; // 服务端注册的能力名称。
public interface ToolExecutor { // 注册表与受控执行器实现同一契约。
    List<JsonNode> schemasForLlm(); // 返回可向模型公开的工具声明。
    Set<String> names(); // 返回执行器实际提供的工具名。
    String invoke(String name, String argumentsJson, long timeoutMs); // 执行一次带等待上限的调用。
} // 结束工具执行接口。
