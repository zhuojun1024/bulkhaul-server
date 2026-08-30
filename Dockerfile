# syntax=docker/dockerfile:1
# bulkhaul-server 多阶段构建（Maven 编译 → JRE 运行）
# 构建：docker build -t blms-backend .
# 运行：docker run -p 8081:8081 --env-file .env blms-backend

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先拷 pom 预拉依赖（利用 Docker 层缓存，源码变更不重拉依赖）
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
# 再拷源码打包（跳过测试：测试由 CI 单独跑，见 C2）
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
# 非 root 运行（安全基线）
RUN groupadd -r blms && useradd -r -g blms blms
# spring-boot-maven-plugin repackage 产物（可执行 fat jar）
COPY --from=build /build/target/*.jar app.jar
USER blms
EXPOSE 8081
ENV SERVER_PORT=8081
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
# 敏感配置经环境变量注入（A4）：DB_URL / DB_USERNAME / DB_PASSWORD / REDIS_HOST / JWT_SECRET 等
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
