# syntax=docker/dockerfile:1
# One build stage compiles every backend service; each service is a separate target.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY contracts contracts
COPY knowledge knowledge
COPY simulator simulator
COPY engine engine
COPY api api
COPY frontend/build.gradle.kts frontend/build.gradle.kts
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -q :simulator:bootJar :engine:bootJar :api:bootJar

# Debian-based JRE (not Alpine): Kafka Streams' RocksDB needs glibc.
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM runtime AS simulator
COPY --from=build /workspace/simulator/build/libs/simulator.jar app.jar

FROM runtime AS engine
COPY --from=build /workspace/engine/build/libs/engine.jar app.jar

FROM runtime AS api
COPY --from=build /workspace/api/build/libs/api.jar app.jar
