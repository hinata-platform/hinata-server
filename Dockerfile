# ---- Build stage ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
# Copy the Gradle wrapper + build definition first so dependency resolution is
# cached in its own layer and only re-runs when the build files change.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src src
RUN ./gradlew --no-daemon -x test clean bootJar

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S hinata && adduser -S hinata -G hinata
USER hinata:hinata
WORKDIR /app
COPY --from=build /workspace/build/libs/hinata-server-*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
