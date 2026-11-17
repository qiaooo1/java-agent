package com.agent4.worker; // Worker 独立于平台 API 部署。
import com.agent4.backend.BackendConfiguration; // 共享数据库事务与队列拓扑。
import org.springframework.boot.SpringApplication; // 启动无 Web 服务器的进程。
import org.springframework.boot.autoconfigure.SpringBootApplication; // 自动配置 JDBC、Redis 和 AMQP。
import org.springframework.context.annotation.Import; // 不扫描旧演示或平台控制器。
import org.springframework.amqp.rabbit.annotation.EnableRabbit; // 开启消息监听容器。
@SpringBootApplication // 仅扫描 Worker 包。
@Import(BackendConfiguration.class) // 统一任务和额度规则。
@EnableRabbit // 并发与预取数量在配置文件中限制。
public class WorkerApplication { // 没有用户可直接调用的内部 HTTP 写接口。
    public static void main(String[] args) { SpringApplication.run(WorkerApplication.class, args); } // 启动消息消费和任务执行。
} // 结束执行服务入口。
