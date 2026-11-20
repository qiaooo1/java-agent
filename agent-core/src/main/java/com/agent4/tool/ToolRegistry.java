package com.agent4.tool; // 注册中心与工具注解同包，作为框架唯一的工具入口。

import com.agent4.util.Json; // 复用项目统一的 ObjectMapper，保证 Schema 与参数序列化一致。
import com.fasterxml.jackson.databind.JsonNode; // 用 Jackson 树模型拼装 OpenAI tools 协议结构。
import com.fasterxml.jackson.databind.node.ObjectNode; // 构造可写的对象节点。

import java.util.ArrayList; // 汇总 Schema 清单与缺失参数名。
import java.util.Collections; // 返回只读视图，防止外部篡改注册表。
import java.util.LinkedHashMap; // 保持注册顺序，使下发给模型的工具清单稳定可复现。
import java.util.List; // 工具清单以列表返回。
import java.util.Map; // 名称到 ToolSpec 的索引，调用时 O(1) 查找。
import java.util.Set; // names() 返回名称集合。
import java.util.concurrent.ExecutionException; // 反射异常被包装，需要解包取真实原因。
import java.util.concurrent.ExecutorService; // 用线程池为工具执行加超时隔离。
import java.util.concurrent.Executors; // 执行器相关类型导入，构造处使用带界线程池。
import java.util.concurrent.Future; // 每次调用返回 Future 以支持带超时等待。
import java.util.concurrent.TimeUnit; // 超时与空闲时间单位。
import java.util.concurrent.TimeoutException; // 超时后取消任务并回传错误观察。

/**
 * 工具注册中心：注解扫描注册、Schema 汇总、统一调用入口。
 *
 * <p>核心设计 —— {@link #invoke} 永不向上抛异常：任何失败（未知工具、JSON 解析失败、
 * 缺参、执行异常、超时）都转为错误字符串作为 Observation 回传，让 Agent 能自愈重试。</p>
 */
public final class ToolRegistry implements ToolExecutor { // 注册中心可由 Harness 组合治理，不允许子类绕过原有校验。

    private final Map<String, ToolSpec> tools = new LinkedHashMap<>(); // 有序注册表，重名在写入时即被拒绝。
    private final ExecutorService executor; // 工具在独立线程执行，避免阻塞 Agent 主循环。
    private final long defaultTimeoutMs; // 默认超时兜底，防止工具永久占用线程。

    public ToolRegistry() { // 无参构造便于 Spring 容器直接实例化。
        this(30_000); // 默认 30 秒，超时即取消并回传错误观察。
    } // 无参构造结束。

    public ToolRegistry(long defaultTimeoutMs) { // 允许测试注入极短超时以验证超时分支。
        this.defaultTimeoutMs = defaultTimeoutMs; // 保存默认超时供两参 invoke 使用。
        this.executor = new java.util.concurrent.ThreadPoolExecutor(4, 16, 60L, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(64), r -> { // 有界线程和队列防止工具超时后线程持续膨胀。
            Thread t = new Thread(r, "tool-worker"); // 固定线程名前缀，便于排查哪个工具卡住。
            t.setDaemon(true); // 守护线程，避免阻止 JVM 退出。
            return t; // 工厂返回已配置线程。
        }); // 核心 4、最大 16、队列 64，超出直接拒绝以免无限堆积。
    } // 有参构造结束。

    /** 扫描实例上所有 @Tool 方法并注册。 */
    public ToolRegistry register(Object toolInstance) { // 返回 this 支持链式注册多个工具实例。
        int count = 0; // 统计本实例注册数，用于发现无工具的空实例。
        for (java.lang.reflect.Method m : toolInstance.getClass().getDeclaredMethods()) { // 只扫本类声明方法，避免父类方法重复注册。
            if (m.isAnnotationPresent(Tool.class)) { // 未标注的方法不是工具，直接忽略。
                ToolSpec spec = ToolSpec.fromAnnotated(toolInstance, m); // 解析注解生成元信息与参数 Schema。
                put(spec); // 写入注册表，重名立即失败。
                count++; // 累加成功注册数量。
            } // 注解判断结束。
        } // 方法扫描循环结束。
        if (count == 0) { // 实例没有任何工具通常是配置错误，应尽早暴露。
            throw new IllegalArgumentException( // 失败信息带上类名便于定位注册代码。
                    "类中没有 @Tool 方法: " + toolInstance.getClass().getName()); // 说明登记失败原因。
        } // 空实例判断结束。
        return this; // 支持链式调用。
    } // register 方法结束。

    private void put(ToolSpec spec) { // 统一写入入口，集中做重名校验。
        if (tools.containsKey(spec.name())) { // 模型按名字调用工具，重名会导致调用歧义。
            throw new IllegalStateException("工具重名: " + spec.name()); // 注册阶段失败而非运行期随机命中。
        } // 重名判断结束。
        tools.put(spec.name(), spec); // 以工具名为键登记。
    } // put 方法结束。

    /** OpenAI tools 协议格式的工具清单。 */
    public List<JsonNode> schemasForLlm() { // 每次按当前注册表生成，新增工具后无需刷新缓存。
        List<JsonNode> out = new ArrayList<>(); // 输出顺序与注册顺序一致。
        for (ToolSpec spec : tools.values()) { // 逐个工具生成 function 声明。
            ObjectNode fn = Json.mapper().createObjectNode() // function 对象承载名字与描述。
                    .put("name", spec.name()) // 模型调用时使用的工具名。
                    .put("description", spec.description()); // 描述是模型选择工具的主要依据。
            fn.set("parameters", spec.parametersSchema()); // 参数 Schema 决定模型如何构造 arguments。
            ObjectNode wrapper = Json.mapper().createObjectNode() // 外层包装是协议要求的 type=function 结构。
                    .put("type", "function"); // 固定类型标识。
            wrapper.set("function", fn); // 挂上函数定义。
            out.add(wrapper); // 追加到清单。
        } // 遍历结束。
        return out; // 返回可直接放入 chat 请求 tools 字段的数组。
    } // schemasForLlm 结束。

    public Set<String> names() { // 暴露名称集合用于错误提示与可用性展示。
        return Collections.unmodifiableSet(tools.keySet()); // 只读视图，防止调用方增删注册表。
    } // names 结束。

    public int size() { // 注册数量，便于启动日志与测试断言。
        return tools.size(); // 返回当前工具数。
    } // size 结束。

    public String invoke(String name, String argumentsJson) { // 两参重载走默认超时。
        return invoke(name, argumentsJson, defaultTimeoutMs); // 委托三参实现，避免逻辑分叉。
    } // 重载方法结束。

    public String invoke(String name, String argumentsJson, long timeoutMs) { // 统一入口：返回值即 Observation，永不向上抛异常。
        try { // 外层兜底，连参数解析等意外都转成错误文本。
            ToolSpec spec = tools.get(name); // 模型可能编造工具名，必须判空。
            if (spec == null) { // 未知工具不应让整轮对话失败。
                return "错误: 未注册的工具 '" + name + "'，可用工具: " + names(); // 列出可用工具，引导模型改写调用。
            } // 未知工具分支结束。
            JsonNode root = parseArgs(argumentsJson); // 解析并强制要求 JSON 对象。
            if (root == null) { // 模型可能返回数组或非法 JSON。
                return "错误: 参数必须是 JSON 对象，收到: " + Json.truncate(argumentsJson, 120); // 截断回显，避免超长内容污染上下文。
            } // 参数形状校验结束。

            List<String> missing = new ArrayList<>(); // 收集全部缺失参数，一次告知而非逐个报错。
            Map<String, Object> values = new LinkedHashMap<>(); // 按声明顺序组装实参，顺序决定反射调用正确性。
            for (ToolSpec.ParamMeta p : spec.params()) { // 以声明元信息为准遍历，忽略模型多传的键。
                JsonNode v = root.get(p.name()); // 按名字取值，缺键得到 null。
                boolean primitive = p.type().isPrimitive(); // 基本类型无法接收 null，缺失即视为必填。
                if (v == null || v.isNull()) { // JSON null 与缺键等价处理。
                    if (p.required() || primitive) { // 必填或基本类型不可为空。
                        missing.add(p.name()); // 记录缺失参数名。
                    } else { // 可选且为引用类型。
                        values.put(p.name(), null); // 显式放 null，保证实参数组长度完整。
                    } // 必填判断结束。
                } else { // 有实际值。
                    values.put(p.name(), Json.mapper().convertValue(v, Object.class)); // 先转 Java 原生值，后续按声明类型转换。
                } // 值处理分支结束。
            } // 参数遍历结束。
            if (!missing.isEmpty()) { // 有缺失则不执行工具，避免半截调用。
                return "错误: 缺少必需参数 " + missing + "，参数定义: " // 回报参数定义，让模型按 Schema 补齐后重试。
                        + Json.write(spec.parametersSchema()); // 附完整 Schema 作为纠正依据。
            } // 缺失分支结束。

            Future<Object> future = executor.submit(() -> spec.call(values)); // 提交到线程池，使超时可以被强制执行。
            try { // 内层只负责等待与超时处理。
                return render(future.get(timeoutMs, TimeUnit.MILLISECONDS)); // 阻塞等待结果并在超时前返回。
            } catch (TimeoutException e) { // 超时视为失败观察，而不是卡死整个 Agent。
                future.cancel(true); // 尽力中断执行线程，释放资源。
                return "错误: 工具执行超时（>" + timeoutMs + "ms）"; // 告知超时阈值，便于模型换更轻量的调用。
            } catch (InterruptedException e) { // 应用关闭或调用线程被取消时停止等待工具。
                future.cancel(true); // 尽力中断底层工具任务。
                Thread.currentThread().interrupt(); // 保留中断状态，交由上层决定退出。
                return "错误: 工具执行已中断"; // 将中断转换为可记录的执行结果。
            } catch (ExecutionException e) { // 反射调用抛出的异常被包装在此。
                Throwable cause = e.getCause() == null ? e : e.getCause(); // 解包取工具内部真实异常。
                return "错误: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(); // 只回传类型与消息，避免堆栈占用上下文。
            } // 执行异常分支结束。
        } catch (IllegalArgumentException e) { // 参数转换等非法输入在此兜底。
            return "错误: " + e.getMessage(); // 转成可重试的观察文本。
        } catch (Exception e) { // 最后一道兜底，保证 invoke 永不抛异常。
            return "错误: " + e; // 极端情况下输出异常 toString。
        } // 外层 try 结束。
    } // invoke 方法结束。

    @jakarta.annotation.PreDestroy // 随 Spring 容器关闭工具执行资源。
    public void closeWorkers() { // 独立测试也可显式调用此清理方法。
        executor.shutdownNow(); // 清理排队任务并尽力中断正在运行的工具。
    } // 结束执行器清理。

    private JsonNode parseArgs(String argumentsJson) { // 参数解析独立成方法，统一空值与形状处理。
        if (argumentsJson == null || argumentsJson.isBlank()) { // 无参工具允许模型传空字符串。
            return Json.mapper().createObjectNode(); // 空串等价空对象，避免解析报错。
        } // 空参分支结束。
        JsonNode node = Json.parse(argumentsJson); // 解析失败会抛异常，由外层转错误观察。
        return node.isObject() ? node : null; // 只接受对象，返回 null 交由调用方统一报错。
    } // parseArgs 结束。

    private static String render(Object result) { // 把任意返回值统一序列化成 Observation 文本。
        if (result == null) { // void 或无返回值工具用 ok 表示成功。
            return "ok"; // 简短成功标记。
        } // null 分支结束。
        if (result instanceof String s) { // 字符串原样返回，避免二次加引号转义。
            return s; // 直接作为观察文本。
        } // 字符串分支结束。
        try { // 非字符串值尝试 JSON 序列化。
            return Json.write(result); // 结构化输出便于模型解析字段。
        } catch (Exception e) { // 无法序列化的对象降级处理。
            return String.valueOf(result); // 退化为 toString，保证始终有结果。
        } // 序列化 try 结束。
    } // render 结束。
} // 类体结束。
