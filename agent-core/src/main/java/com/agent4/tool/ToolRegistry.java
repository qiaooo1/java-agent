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
 * 缺参、执行异常、超时）都转为安全结果；结构化恢复通过 execute 与 Harness 完成。</p>
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

    public String invoke(String name, String argumentsJson, long timeoutMs) { // 旧接口只保留展示兼容，内部恢复不解析文本。
        return execute(name, argumentsJson, timeoutMs).legacyObservation(); // 成功数据不变，失败输出安全分类。
    } // 结束兼容工具入口。

    @Override public boolean retrySafe(String name) { // 不把恢复权限暴露为模型可传入的参数。
        ToolSpec spec = tools.get(name); return spec != null && spec.retrySafe(); // 未注册或未声明一律禁止自动重试。
    } // 结束恢复能力查询。

    @Override public ToolResult execute(String name, String argumentsJson, long timeoutMs) { // 此处只执行一次，重试由 Harness 的总预算治理。
        if (Thread.currentThread().isInterrupted()) return ToolResult.failure(ToolErrorCode.CANCELLED); // 中断后不提交新的工具工作。
        if (timeoutMs < 1) return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT); // 非正超时不能触发工具执行。
        ToolSpec spec = tools.get(name); // 工具名只能从服务端注册表解析。
        if (spec == null) return ToolResult.failure(ToolErrorCode.UNKNOWN_TOOL); // 不回显未知名称或整个工具目录。
        try { // 参数转换与执行错误均映射为受控结果。
            JsonNode root; // 参数解析失败不能暴露原始 JSON 片段。
            try { root = parseArgs(argumentsJson); } catch (RuntimeException invalid) { return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT); } // 非法 JSON 需要模型修正。
            if (root == null) return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT); // 只允许 JSON 对象。
            Map<String, Object> values = new LinkedHashMap<>(); // 保留方法参数声明顺序。
            for (ToolSpec.ParamMeta parameter : spec.params()) { // 在任何真实调用前验证必填字段。
                JsonNode value = root.get(parameter.name()); // 不回显缺失字段的模型原始数据。
                if (value == null || value.isNull()) { // 缺键和显式 null 一致处理。
                    if (parameter.required() || parameter.type().isPrimitive()) return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT); // 拒绝不完整调用。
                    values.put(parameter.name(), null); // 可选引用类型保持 null。
                } else values.put(parameter.name(), Json.mapper().convertValue(value, Object.class)); // 实际类型转换由 ToolSpec 完成。
            } // 完成执行前参数检查。
            Future<Object> future = executor.submit(() -> spec.call(values)); // 真实调用仍在有界工具线程池中执行。
            try { // 等待上限由 Harness 剩余运行预算进一步收紧。
                return ToolResult.success(render(future.get(timeoutMs, TimeUnit.MILLISECONDS))); // 用类型保留正常业务输出。
            } catch (TimeoutException timeout) { // 超时不保证工具或远端副作用已停止。
                future.cancel(true); return ToolResult.failure(ToolErrorCode.TIMEOUT); // 明确分类，禁止不确定状态下自动重放。
            } catch (InterruptedException cancelled) { // 监听线程中断时同时取消底层等待。
                future.cancel(true); Thread.currentThread().interrupt(); return ToolResult.failure(ToolErrorCode.CANCELLED); // 保留停止信号。
            } // 方法和类型转换异常交由下方统一解包分类。
        } catch (Exception failure) { // 不把异常消息、堆栈或凭据放进 Observation。
            Throwable cause = failure; // 只解包已知的异步与反射包装，不任意追逐业务原因链。
            while ((cause instanceof ExecutionException || cause instanceof java.lang.reflect.InvocationTargetException) && cause.getCause() != null) cause = cause.getCause(); // 找到工具真实失败类型。
            ToolErrorCode code = classify(cause); // 分类基于类型或适配器显式错误，而非错误字符串。
            if (code == ToolErrorCode.EXECUTION_FAILED) System.getLogger(ToolRegistry.class.getName()).log(System.Logger.Level.WARNING, "tool_failed tool={0} type={1}", spec.name(), cause.getClass().getName()); // 服务端只记录工具名和异常类型。
            return ToolResult.failure(code); // 公共结果只包含常量消息和恢复建议。
        } // 完成单次真实执行。
    } // 结束结构化工具入口。

    private static ToolErrorCode classify(Throwable failure) { // 默认内部错误不可自动重试。
        if (failure instanceof ToolExecutionException typed) return typed.code(); // 受控适配器可以提供更精确的故障类别。
        if (failure instanceof SecurityException) return ToolErrorCode.PERMISSION_DENIED; // 权限错误不能当参数或网络错误处理。
        if (failure instanceof java.net.SocketTimeoutException) return ToolErrorCode.TIMEOUT; // 读取超时状态不确定，保守终止。
        if (failure instanceof InterruptedException) return ToolErrorCode.CANCELLED; // 工具主动中断也终止执行。
        if (failure instanceof java.util.concurrent.RejectedExecutionException) return ToolErrorCode.RESOURCE_EXHAUSTED; // 线程池关闭或饱和时不忙重试。
        if (failure instanceof IllegalArgumentException) return ToolErrorCode.INVALID_ARGUMENT; // 包括实际参数类型转换失败。
        if (failure instanceof java.io.FileNotFoundException || failure instanceof java.nio.file.NoSuchFileException) return ToolErrorCode.EXECUTION_FAILED; // 缺失文件不能靠立即重试修复。
        if (failure instanceof java.io.IOException) return ToolErrorCode.TRANSIENT_FAILURE; // 只有工具另行声明安全，Harness 才会重试可能的传输故障。
        return ToolErrorCode.EXECUTION_FAILED; // 未知异常保守失败，禁止暴露内部消息。
    } // 结束失败分类。

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
            throw new ToolExecutionException(ToolErrorCode.INVALID_RESPONSE); // 序列化失败不回显内部对象。
        } // 序列化 try 结束。
    } // render 结束。
} // 类体结束。
