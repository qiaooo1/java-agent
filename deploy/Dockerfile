FROM eclipse-temurin:17-jre-alpine
# Java 17 与 Maven 编译基线一致，运行镜像不需要 Maven。
WORKDIR /app
# 所有应用使用相同工作目录，避免相对路径不一致。
ARG JAR_FILE
# 每个服务只复制自己的打包产物，不复制源码和密钥。
COPY ${JAR_FILE} app.jar
# 镜像构建前必须先完成 mvn package。
USER 10001:10001
# 应用以非 root 用户运行，不需要写入镜像目录。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
# 显式统一时区，留出堆外内存供网络和线程使用。
