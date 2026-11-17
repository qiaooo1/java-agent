package com.agent4.util; // 工具与 Agent 共用同一 JSON 门面，避免各处重复创建 ObjectMapper。

import com.fasterxml.jackson.databind.JsonNode; // JsonNode 作为解析结果的统一返回类型。
import com.fasterxml.jackson.databind.ObjectMapper; // 全局复用线程安全的 ObjectMapper。

/**
 * 全局共享的 Jackson ObjectMapper 与 JSON 便捷方法。
 */
public final class Json { // 工具类不可实例化，全部方法静态。

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 单例 mapper：ObjectMapper 线程安全且创建昂贵，重复创建会拖慢启动。

    private Json() { // 私有构造阻止外部 new。
    } // 空构造体仅用于关闭默认构造入口。

    public static ObjectMapper mapper() { // 暴露 mapper 给需要自定义读写的调用方，避免重复配置。
        return MAPPER; // 返回共享实例，调用方不得修改其配置。
    } // 结束 mapper。

    public static String write(Object o) { // 序列化入口：失败统一转成运行期异常，便于上层识别。
        try { // try 包裹受检异常，调用方不必到处写 try。
            return MAPPER.writeValueAsString(o); // 直接写字符串，供 API 响应和落盘使用。
        } catch (Exception e) { // 捕获所有序列化失败，包括类型不可序列化。
            throw new IllegalArgumentException("JSON 序列化失败", e); // 包装成运行期异常并保留原始原因，便于定位具体字段。
        } // 结束序列化异常处理。
    } // 结束 write。

    public static JsonNode parse(String json) { // 解析入口返回树模型，调用方按路径取值而无需定义类。
        try { // 同样把受检异常统一转成运行期异常。
            return MAPPER.readTree(json); // readTree 容忍字段顺序变化，适合解析工具参数。
        } catch (Exception e) { // 捕获语法错误与 IO 问题，均视为调用方输入非法。
            throw new IllegalArgumentException("JSON 解析失败: " + json, e); // 带上原始 json 便于定位是哪个请求体损坏。
        } // 结束解析异常处理。
    } // 结束 parse。

    public static String truncate(String s, int max) { // 截断用于日志与错误文本，避免超长内容淹没输出。
        if (s == null) { // null 原样返回，保持调用方判空语义不变。
            return null; // 返回 null，调用方无需再处理空值。
        } // 结束 null 保护分支。
        return s.length() <= max ? s : s.substring(0, max) + "..."; // 超上限才截断并加省略号，提示内容不完整。
    } // 结束 truncate。
} // 结束 Json 工具类。
