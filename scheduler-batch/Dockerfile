# syntax=docker/dockerfile:1
# Build context: repo root (docker compose sets it), e.g.
#   docker build -f scheduler-batch/Dockerfile -t keyloop/scheduler-batch .

# ---- Stage 1: build only this module with the Gradle wrapper ----
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# Build scripts first: this layer changes rarely
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY common-logging/build.gradle.kts common-logging/
COPY server-api/build.gradle.kts server-api/
COPY scheduler-batch/build.gradle.kts scheduler-batch/

# Sources of this module only — edits to the other module do not bust the cache
COPY common-logging/src common-logging/src
COPY scheduler-batch/src scheduler-batch/src

# Tests run in './gradlew build', not in the image build
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :scheduler-batch:bootJar --no-daemon -x test

# Split the fat jar into layers (dependencies change less often than app code)
RUN java -Djarmode=tools -jar scheduler-batch/build/libs/scheduler-batch.jar extract --layers --destination /extracted

# ---- Stage 2: JRE-only runtime ----
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
# Log4j2 writes here (LOG_PATH); docker compose bind-mounts ./logs over it
RUN mkdir -p /app/logs && chown app:app /app/logs

COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

USER app
EXPOSE 8081
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8081/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar scheduler-batch.jar"]
