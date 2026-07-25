# ---- build stage: compile the Spring Boot fat jar with JDK 25 ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
# Warm the Gradle wrapper / dependency cache separately from source for faster rebuilds.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

# ---- runtime stage: slim JRE ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
# Run as a non-root user.
RUN useradd -r -u 10001 audit
COPY --from=build /app/build/libs/*.jar /app/app.jar
USER audit
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
