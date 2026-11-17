package com.agent4.platform; // 后台任务不能共用唯一调度线程，避免 MQ 确认等待拖住租约恢复。
import org.springframework.context.annotation.*; // 声明调度 Bean。
import org.springframework.scheduling.TaskScheduler; // 使用 Spring 调度接口。
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler; // 有界线程池承载独立周期任务。
@Configuration // 自动被平台扫描，Worker 使用自己的心跳池。
public class PlatformScheduling { // 调度并行不改变数据库条件领取的正确性。
    @Bean TaskScheduler taskScheduler() { // 使用 Spring 默认识别的调度器名称。
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler(); // 统一管理调度线程生命周期。
        scheduler.setPoolSize(4); scheduler.setThreadNamePrefix("platform-schedule-"); // 发布、恢复和 SSE 安排可以独立推进。
        scheduler.setWaitForTasksToCompleteOnShutdown(false); // 关闭时不无限等待外部中间件。
        return scheduler; // 容器负责初始化与销毁。
    } // 结束调度器配置。
} // 结束后台调度配置。
