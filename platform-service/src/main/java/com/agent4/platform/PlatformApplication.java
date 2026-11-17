package com.agent4.platform; // 平台只扫描自己的入口组件。
import com.agent4.backend.BackendConfiguration; // 显式导入共享事务与中间件配置。
import org.springframework.boot.SpringApplication; // 启动独立平台进程。
import org.springframework.boot.autoconfigure.SpringBootApplication; // 自动装配 Web、数据库与安全组件。
import org.springframework.context.annotation.Import; // 避免扫描旧演示控制器。
import org.springframework.scheduling.annotation.EnableScheduling; // 开启 Outbox 和租约恢复调度。
@SpringBootApplication // 主包为 com.agent4.platform。
@Import(BackendConfiguration.class) // 共享后端模块只通过明确入口引入。
@EnableScheduling // 多实例调度由数据库条件领取保证正确性。
public class PlatformApplication { // 平台不直接执行耗时 Agent。
    public static void main(String[] args) { SpringApplication.run(PlatformApplication.class, args); } // 启动用户 API、静态页面与事件订阅。
} // 结束启动类。
