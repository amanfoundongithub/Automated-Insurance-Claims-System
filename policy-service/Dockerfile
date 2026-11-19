FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src

RUN chmod +x gradlew \
    && ./gradlew --no-daemon bootJar \
    && jar_path="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && test -n "$jar_path" \
    && cp "$jar_path" /workspace/app.jar

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app.jar app.jar
USER 10001:10001

EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
