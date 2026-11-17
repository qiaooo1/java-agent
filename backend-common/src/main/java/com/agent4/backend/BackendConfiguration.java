package com.agent4.backend; // 公共基础设施装配，不扫描旧演示模块。
import org.springframework.context.annotation.*; // 声明共享 Bean。
import org.springframework.amqp.core.*; // 声明持久化交换机、队列和死信绑定。
import org.springframework.amqp.rabbit.core.RabbitTemplate; // 配置消息必须可路由。
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer; // 使用 Boot 自动配置的确认连接。
@Configuration // 平台与 Worker 显式导入此配置。
@ComponentScan(basePackageClasses = TaskStore.class) // 只扫描共享后端模块。
public class BackendConfiguration { // 队列拓扑在两个服务启动时幂等声明。
    public static final String EXCHANGE = "workbench.tasks"; // 工作交换机。
    public static final String QUEUE = "workbench.execute"; // 待执行任务队列。
    public static final String DEAD = "workbench.dead"; // 非法消息与不可恢复消息的归宿。
    @Bean Declarables taskTopology() { // 将完整拓扑作为一个声明单元。
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false); // 重启后保留交换机。
        DirectExchange dead = new DirectExchange(DEAD, true, false); // 持久化死信交换机。
        Queue queue = QueueBuilder.durable(QUEUE).deadLetterExchange(DEAD).deadLetterRoutingKey("dead").build(); // 业务失败由状态机重试，非法消息进入死信。
        Queue deadQueue = QueueBuilder.durable(DEAD).build(); // 死信可供人工排查。
        return new Declarables(exchange, dead, queue, deadQueue, BindingBuilder.bind(queue).to(exchange).with("execute"), BindingBuilder.bind(deadQueue).to(dead).with("dead")); // 显式绑定，避免确认成功却无人接收。
    } // 完成拓扑声明。
    @Bean RabbitTemplateCustomizer mandatoryMessages() { // Boot 保持 correlated confirms 配置。
        return template -> template.setMandatory(true); // 无路由消息必须退回发布器。
    } // 完成发送策略配置。
} // 结束公共装配。
