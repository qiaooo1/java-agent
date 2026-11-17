package com.agent4.tool; // 参数元注解与 Tool 同包，构成工具声明的配套契约。

import java.lang.annotation.ElementType; // 注解适用目标的枚举。
import java.lang.annotation.Retention; // 声明保留策略。
import java.lang.annotation.RetentionPolicy; // RUNTIME 策略常量。
import java.lang.annotation.Target; // 限定注解作用目标。

/**
 * 工具参数的元信息。参数名取 Java 形参名（编译期 -parameters），
 * {@link #value()} 作为描述写入 JSON Schema。
 */
@Retention(RetentionPolicy.RUNTIME) // 运行期可读，构建 ToolSpec 时据此生成 Schema。
@Target(ElementType.PARAMETER) // 只能加在方法参数上，防止误用到字段。
public @interface ToolParam { // 注解只补充描述与必填性，参数名来自方法签名。

    /** 参数说明，进入 Schema 的 description。 */
    String value() default ""; // 空描述时 Schema 省略该字段，避免冗余。

    /** 是否必填，缺省 true。 */
    boolean required() default true; // 默认必填，漏传会在调用前的参数校验中报错。
} // 注解类型结束。
