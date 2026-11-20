FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
# Run as an unprivileged user, not root
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /app/target/docs-chat-1.0.0.jar app.jar
# Hosted: listen on all interfaces inside the container and trust the host's proxy headers.
# The RequestGuard still only answers to ALLOWED_HOSTS.
ENV SERVER_ADDRESS=0.0.0.0 FORWARD_HEADERS=native JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8081
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
