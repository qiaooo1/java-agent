package com.agent4.gateway; // 网关只依赖路由、认证与 Redis，不直接访问业务库。
import org.springframework.boot.SpringApplication; // 启动响应式应用。
import org.springframework.boot.autoconfigure.SpringBootApplication; // 自动装配 Gateway 与 WebFlux。
@SpringBootApplication // 仅扫描当前模块。
public class GatewayApplication { // 外部客户端统一入口。
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); } // 默认监听 8080。
} // 结束网关入口。
