package com.agent4.tool; // 工具元信息与注册中心同包，只由 ToolRegistry 构造使用。

import com.agent4.util.Json; // 复用统一 mapper 完成 Schema 生成与复杂类型转换。
import com.fasterxml.jackson.databind.JsonNode; // 以通用树节点返回 Schema。
import com.fasterxml.jackson.databind.node.ArrayNode; // required 字段是 JSON 数组。
import com.fasterxml.jackson.databind.node.ObjectNode; // properties 字段是 JSON 对象。

import java.lang.reflect.Method; // 反射调用目标方法。
import java.lang.reflect.Parameter; // 读取形参名与注解。
import java.util.ArrayList; // 按顺序构建参数元信息。
import java.util.List; // 参数元信息列表类型。

/**
 * 单个工具的元信息：名称、描述、参数元数据，以及 JSON Schema 生成与反射调用。
 */
public final class ToolSpec { // final：元信息不可变，注册后不应被外部修改。

    /** 单个参数元信息。 */
    public record ParamMeta(String name, String description, boolean required, Class<?> type) { // record 天然不可变，四个字段覆盖 Schema 与类型转换所需。
    } // record 定义结束。

    private final Object instance; // 工具方法所属实例，注册时固定。
    private final Method method; // 反射入口，私有方法经 setAccessible 也可调用。
    private final String name; // 对外工具名，模型按此名调用。
    private final String description; // 功能描述，进入模型工具清单。
    private final List<ParamMeta> params; // 参数声明顺序，决定反射实参顺序。

    private ToolSpec(Object instance, Method method, String name, String description, // 私有构造强制经 fromAnnotated 生成，避免漏校验注解。
                     List<ParamMeta> params) { // 参数列表由注解扫描得出。
        this.instance = instance; // 绑定实例。
        this.method = method; // 绑定反射方法。
        this.name = name; // 保存最终工具名。
        this.description = description; // 保存功能描述。
        this.params = params; // 保存声明顺序的参数清单。
    } // 构造结束。

    /** 从带 @Tool 注解的方法构建 ToolSpec。 */
    public static ToolSpec fromAnnotated(Object instance, Method method) { // 唯一构建入口，元信息全部从注解与方法签名推导。
        Tool ann = method.getAnnotation(Tool.class); // 调用方已确认标注，这里直接读取。
        String name = ann.name().isBlank() ? method.getName() : ann.name(); // 未命名时回退方法名，空串与空白都按未命名处理。
        List<ParamMeta> params = new ArrayList<>(); // 按方法参数顺序收集元信息。
        for (Parameter p : method.getParameters()) { // 依赖编译期 -parameters 才能拿到真实形参名。
            ToolParam tp = p.getAnnotation(ToolParam.class); // 注解可为空，未标注参数仍要进入 Schema。
            String desc = tp == null ? "" : tp.value(); // 无注解时描述为空，Schema 会省略 description。
            boolean required = tp == null || tp.required(); // 未标注默认必填，与 ToolParam 默认值保持一致。
            params.add(new ParamMeta(p.getName(), desc, required, p.getType())); // 记录名称、描述、必填性与声明类型。
        } // 参数遍历结束。
        return new ToolSpec(instance, method, name, ann.description(), params); // 描述无默认值，保证每个工具写明用途。
    } // fromAnnotated 结束。

    public String name() { // 暴露工具名供注册表索引。
        return name; // 返回固定名称。
    } // name 方法结束。
    public boolean retrySafe() { return method.getAnnotation(Tool.class).retrySafe(); } // 恢复权限来自工具作者，不能由模型参数开启。

    public String description() { // 暴露描述用于生成工具清单。
        return description; // 返回描述文本。
    } // description 方法结束。

    public List<ParamMeta> params() { // 暴露参数元信息供 invoke 做缺失校验。
        return params; // 返回声明顺序的参数列表。
    } // params 方法结束。

    /** 反射调用工具方法，返回值作为 Observation。 */
    public Object call(java.util.Map<String, Object> args) throws Exception { // 反射调用；异常交给上层包装成错误观察。
        Object[] arr = new Object[params.size()]; // 实参数组必须与形参数量一致，缺失位置由调用方补 null。
        for (int i = 0; i < params.size(); i++) { // 严格按声明顺序填充，顺序错会类型错配。
            arr[i] = convert(args.get(params.get(i).name()), params.get(i).type()); // 先按参数名取值，再转声明类型。
        } // 填充循环结束。
        return method.invoke(instance, arr); // 反射执行并返回工具结果。
    } // call 方法结束。

    /** 把 JSON 反序列化出的值转换成参数声明的类型。 */
    private static Object convert(Object v, Class<?> type) { // 类型转换集中在此，避免每个工具重复解析字符串。
        if (v == null) { // null 仅允许传给引用类型，基本类型缺失已在 invoke 阶段拦截。
            return null; // 透传空值。
        } // null 分支结束。
        if (type.isInstance(v)) { // 类型已匹配时直接使用，避免多余转换。
            return v; // 原样返回。
        } // 类型匹配分支结束。
        if (type == String.class) { // 字符串参数兼容模型传入的数字等标量。
            return v.toString(); // 统一转为文本。
        } // String 分支结束。
        if (type == int.class || type == Integer.class) { // 整数参数。
            return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString()); // 数值直接取值，字符串解析，非法值抛异常由上层转错误。
        } // int 分支结束。
        if (type == long.class || type == Long.class) { // 长整型参数。
            return v instanceof Number n ? n.longValue() : Long.parseLong(v.toString()); // 避免 JSON 数字被当作 Integer 截断。
        } // long 分支结束。
        if (type == double.class || type == Double.class) { // 浮点参数。
            return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString()); // 数值直接取 double，字符串按小数解析。
        } // double 分支结束。
        if (type == boolean.class || type == Boolean.class) { // 布尔参数。
            return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString()); // 非布尔按字符串宽松解析，仅 "true" 为真。
        } // boolean 分支结束。
        return Json.mapper().convertValue(v, type); // 其余复杂类型交给 Jackson，失败抛异常转错误观察。
    } // convert 方法结束。

    /** 生成参数的 JSON Schema（OpenAI tools.parameters 格式）。 */
    public JsonNode parametersSchema() { // 每次按当前元信息生成，避免 Schema 与实现漂移。
        ObjectNode root = Json.mapper().createObjectNode(); // 根节点描述整个参数对象。
        root.put("type", "object"); // OpenAI 要求 parameters 是 object。
        ObjectNode props = root.putObject("properties"); // properties 列出每个参数。
        ArrayNode required = root.putArray("required"); // required 是参数名数组。
        for (ParamMeta p : params) { // 逐个参数生成属性定义。
            ObjectNode prop = props.putObject(p.name()); // 以参数名作为属性键。
            prop.put("type", jsonType(p.type())); // 映射 Java 类型到 JSON Schema 类型。
            if (!p.description().isBlank()) { // 空描述不写字段，避免 Schema 冗余。
                prop.put("description", p.description()); // 描述直接影响模型构造参数的质量。
            } // 描述判断结束。
            if (p.required()) { // 必填参数进入 required 数组。
                required.add(p.name()); // 登记参数名。
            } // 必填判断结束。
        } // 参数遍历结束。
        return root; // 返回完整 Schema。
    } // parametersSchema 结束。

    private static String jsonType(Class<?> type) { // Java 类型到 JSON Schema 类型名的唯一映射点。
        if (type == String.class) { // 文本类型。
            return "string"; // 字符串类型名。
        } // String 分支结束。
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) { // 所有整型统一映射为 integer。
            return "integer"; // 整数类型名。
        } // 整型分支结束。
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) { // 浮点统一映射为 number。
            return "number"; // 数值类型名。
        } // 浮点分支结束。
        if (type == boolean.class || type == Boolean.class) { // 布尔类型。
            return "boolean"; // 布尔类型名。
        } // 布尔分支结束。
        return "string"; // 其余类型按文本描述，避免复杂 Schema 干扰模型。
    } // jsonType 方法结束。
} // 类体结束。
