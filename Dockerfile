# Build the orchestrator
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package

# Runtime = JDK 25 (it is also the JDK every migrated service is built with) + git + Maven (+ gh for PRs)
FROM eclipse-temurin:25-jdk
RUN apt-get update \
 && apt-get install -y --no-install-recommends git maven ca-certificates \
 && (apt-get install -y --no-install-recommends gh || echo "gh not available: install it or set open-pull-request=false") \
 && rm -rf /var/lib/apt/lists/*

ENV JDK25_HOME=/opt/java/openjdk \
    BIND_ADDRESS=0.0.0.0
WORKDIR /app
COPY --from=build /src/target/java-migration-agent-0.1.0.jar app.jar
# Put services.yml next to the jar (mount it) and set MIGRATION_MODE=REAL, ANTHROPIC_API_KEY, GH_TOKEN,
# MIGRATION_API_TOKEN. Mount ~/.m2 as a volume so 30 parallel builds share one warm dependency cache.
VOLUME ["/app/work"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
