# Builds and runs the application in a container.
#   docker build -t food-rescue .
# For the app together with a PostgreSQL database, use docker-compose.yml instead.

# ---- Build stage: compiles the jar with Maven, then is thrown away ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Dependencies first, so they are cached and only downloaded again when pom.xml changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- Runtime stage: only a Java runtime and the jar ----
FROM eclipse-temurin:21-jre
WORKDIR /app
# Run as an unprivileged user rather than root.
RUN useradd --system --uid 1001 foodrescue
COPY --from=build /src/target/food-rescue-*.jar app.jar
USER foodrescue
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
