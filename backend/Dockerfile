# Production image for the backend (deployed to the VPS behind Caddy; docs/ARCHITECTURE.md §8).
# Build from the backend directory:  docker build -t strategy-backend .
# Tests are not run here; CI runs `./mvnw verify` before images are built.

FROM eclipse-temurin:24-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -DskipTests package \
 && java -Djarmode=tools -jar target/strategy-backend-*.jar extract --layers --launcher --destination target/extracted

FROM eclipse-temurin:24-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
# Least-changing layers first for better image layer caching.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./
USER app
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
# Ready = started and the database answers (docs/PRODUCTION_READINESS.md §3). curl ships with the base image.
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=3 \
  CMD curl -fsS "http://localhost:${SERVER_PORT:-8080}/actuator/health/readiness" > /dev/null || exit 1
# Leave room for the OS and threads in the container's memory limit; restart cleanly instead of limping on.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
