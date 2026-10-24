# Two stages: build the jar with Maven, then run it on a small Java 17 runtime.
# Render provides PORT at runtime; the app reads it from configuration (server.port=${PORT}).

FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
# Render builds from the repository root, so the backend folder is copied explicitly.
COPY backend/ ./
RUN chmod +x mvnw && ./mvnw -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Not running as root inside the container.
RUN useradd --system --no-create-home appuser
USER appuser
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
