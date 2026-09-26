# syntax=docker/dockerfile:1
# Build stage: dependencies are resolved in their own layer so code changes rebuild quickly.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -q -DskipTests package

# Runtime stage: JRE only, non-root user.
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /src/target/ai-microservice-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
