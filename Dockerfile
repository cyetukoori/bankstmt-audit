# ---- build stage: compile the Spring Boot fat jar with Amazon Corretto 25 ----
# Amazon's registry (public.ecr.aws), multi-arch: builds natively on Graviton (arm64) and x86_64.
FROM public.ecr.aws/amazoncorretto/amazoncorretto:25-al2023 AS build
# gradlew needs xargs (findutils); the AL2023 base image is minimal.
RUN dnf install -y findutils tar gzip && dnf clean all
WORKDIR /app
# Warm the Gradle wrapper / dependency cache separately from source for faster rebuilds.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

# ---- runtime stage: headless Corretto 25 ----
FROM public.ecr.aws/amazoncorretto/amazoncorretto:25-al2023-headless AS runtime
WORKDIR /app
# Run as a numeric non-root UID: the minimal Corretto image has no useradd, and none is needed.
COPY --from=build --chown=10001:10001 /app/build/libs/*.jar /app/app.jar
ENV HOME=/tmp
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
