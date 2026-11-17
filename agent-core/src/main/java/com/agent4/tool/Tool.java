package com.agent4.tool; // 工具注解与注册中心同包，约定统一的扩展入口。

import java.lang.annotation.ElementType; // 说明注解可标注的元素种类。
import java.lang.annotation.Retention; // 声明注解的保留策略。
import java.lang.annotation.RetentionPolicy; // 指定 RUNTIME 策略常量。
import java.lang.annotation.Target; // 限定注解的作用目标。

/**
 * 标记一个方法为可被 Agent 调用的工具。方法所在实例整体注册进
 * {@link ToolRegistry}，框架自动生成 JSON Schema、校验参数并反射调用。
 */
@Retention(RetentionPolicy.RUNTIME) // 必须 RUNTIME，否则注册中心扫描时读不到注解。
@Target(ElementType.METHOD) // 只允许方法级标注，实例注册后再统一扫描。
public @interface Tool { // @interface 生成可被反射读取的注解类型。

    /** 工具名，缺省使用方法名。 */
    String name() default ""; // 空串表示沿用方法名，保证只写 description 也能注册。

    /** 工具功能描述，进入模型工具清单，质量直接影响模型选型准确率。 */
    String description(); // 无默认值即必填，强制作者写清用途。
} // 注解类型结束。
