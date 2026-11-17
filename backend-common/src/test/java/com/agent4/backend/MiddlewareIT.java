package com.agent4.backend; // 真实 Redis Lua 与 RabbitMQ 确认需要容器验证。
import org.testcontainers.containers.GenericContainer; // 启动隔离 Redis，避免与 JUnit 的 Container 注解重名。
import org.testcontainers.containers.RabbitMQContainer; // 启动隔离 broker。
import org.testcontainers.junit.jupiter.*; // 管理容器生命周期。
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory; // 使用与应用相同 Redis 驱动。
import org.springframework.data.redis.core.StringRedisTemplate; // 执行正式 Lua 槽位逻辑。
import org.springframework.amqp.rabbit.connection.*; // 真实 publisher confirms。
import org.springframework.amqp.rabbit.core.*; // 声明拓扑并发布消息。
import org.springframework.amqp.core.*; // 持久化消息与队列。
import org.junit.jupiter.api.Test; // 集成回归场景。
import static org.junit.jupiter.api.Assertions.*; // 验证容量与路由确认。
import java.util.concurrent.TimeUnit; // 限制确认等待。
@Testcontainers // 只由 -Pintegration 的 failsafe 执行。
class MiddlewareIT { // 不用内存 Map 假装验证 Lua 原子性。
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2.4-alpine").withExposedPorts(6379); // 使用正式 Redis 服务端。
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13.7-management-alpine"); // 使用真实 broker。
    @Test void slotsRespectGlobalLimitsAndOwnership() { // 同一供应商的不同用户也必须共享容量。
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379)); factory.afterPropertiesSet(); factory.start(); // 连接隔离 Redis。
        try { // 无论断言结果都关闭网络资源。
            RedisSlots slots = new RedisSlots(new StringRedisTemplate(factory), 1, 2); // 每用户一个，总供应商两个。
            assertTrue(slots.acquire("a", "one")); assertFalse(slots.acquire("a", "two")); // 用户上限不能突破。
            assertTrue(slots.acquire("b", "two")); assertFalse(slots.acquire("c", "three")); // 总容量同样受限。
            slots.release("a", "wrong-token"); assertFalse(slots.acquire("c", "three")); // 不能释放其他执行尝试的容量。
            assertTrue(slots.refresh("a", "one")); slots.release("a", "one"); assertTrue(slots.acquire("c", "three")); // 正确释放后才能重新占位。
        } finally { factory.destroy(); } // 结束 Redis 连接。
    } // 结束 Redis 原子控制测试。
    @Test void brokerConfirmsAndReturnsAreBothChecked() throws Exception { // ACK 不能替代路由检查。
        CachingConnectionFactory factory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort()); factory.setUsername(RABBIT.getAdminUsername()); factory.setPassword(RABBIT.getAdminPassword()); factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); factory.setPublisherReturns(true); // 开启与正式配置相同的确认和退回。
        try { // 所有测试资源都来自隔离容器。
            RabbitAdmin admin = new RabbitAdmin(factory); DirectExchange exchange = new DirectExchange("it.exchange"); org.springframework.amqp.core.Queue queue = new org.springframework.amqp.core.Queue("it.queue"); // 声明有路由与无路由对照。
            admin.declareExchange(exchange); admin.declareQueue(queue); admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("ok")); // 创建明确路由。
            RabbitTemplate template = new RabbitTemplate(factory); template.setMandatory(true); // 要求不可路由消息退回。
            CorrelationData good = new CorrelationData("good"); template.send("it.exchange", "ok", MessageBuilder.withBody(new byte[]{1}).setDeliveryMode(MessageDeliveryMode.PERSISTENT).build(), good); // 发送可路由消息。
            assertTrue(good.getFuture().get(5, TimeUnit.SECONDS).isAck()); assertNull(good.getReturned()); // 正确路由应被确认且无退回。
            CorrelationData bad = new CorrelationData("bad"); template.send("it.exchange", "missing", new Message(new byte[]{2}, new MessageProperties()), bad); // 模拟交换机存在但没有绑定。
            assertTrue(bad.getFuture().get(5, TimeUnit.SECONDS).isAck()); assertNotNull(bad.getReturned()); // broker ACK 与无路由退回可以同时存在。
        } finally { factory.destroy(); } // 关闭 AMQP 连接。
    } // 结束 broker 确认测试。
} // 结束真实中间件测试。
