FROM gradle:8.10-jdk21 AS build
WORKDIR /workspace
COPY build.gradle.kts settings.gradle.kts ./
COPY src src
RUN gradle bootJar -x test --no-daemon --console=plain
RUN java -Djarmode=layertools -jar build/libs/app.jar extract --destination build/extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S finflow && adduser -S finflow -G finflow
WORKDIR /app
COPY --from=build /workspace/build/extracted/dependencies/ ./
COPY --from=build /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/build/extracted/application/ ./
USER finflow
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
