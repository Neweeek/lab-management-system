FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
# 镜像构建时跳过测试是刻意的：镜像构建只负责打包，测试由 CI/`mvn test` 把关。
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
# sqlite3 是 deploy/backup.sh 的依赖（它执行 sqlite3 ".backup"），不要移除；
# wget 由 busybox 提供，无需额外安装。
RUN apk add --no-cache sqlite
WORKDIR /app
# 版本号来自 pom.xml，改用通配避免版本升级后此处不同步。
COPY --from=build /build/target/lab-seat-system-*.jar app.jar
RUN mkdir -p /app/data /app/logs && chown -R 1000:1000 /app
# SQLite 的时间函数以 UTC 为基准，迁移脚本用 '+8 hours' 换算；固定 TZ 保证行为一致。
ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    APP_DB_PATH=/app/data/lab.db \
    APP_LOG_PATH=/app/logs/application.log
# 以非 root 运行：容器内不再有 root 权限，宿主机 bind mount 里的文件也不会变成 root 所有。
USER 1000:1000
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/api/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
