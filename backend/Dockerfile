FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache sqlite wget
WORKDIR /app
COPY --from=build /build/target/lab-seat-system-0.1.0.jar app.jar
RUN mkdir -p /app/data /app/logs
ENV SPRING_PROFILES_ACTIVE=prod \
    APP_DB_PATH=/app/data/lab.db \
    APP_LOG_PATH=/app/logs/application.log
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/api/seats || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
