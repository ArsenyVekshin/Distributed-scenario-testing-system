FROM gradle:9.5.0-jdk21 AS builder
WORKDIR /app
# Сборка через Wrapper — та же версия Gradle, что и локально; JDK 21 из образа (совместимость с Kotlin/Gradle DSL).
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY src ./src
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew bootJar --no-daemon -x test \
    && cp build/libs/*.jar /app/application.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/application.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
