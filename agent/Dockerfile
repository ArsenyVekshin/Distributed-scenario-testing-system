FROM gradle:9.5.0-jdk21 AS builder
WORKDIR /app
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY src ./src
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install git and Docker CLI for build/test activities
RUN apk add --no-cache git docker-cli

COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
