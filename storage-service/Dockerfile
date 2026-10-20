# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the whole multi-module project so the parent pom + sibling modules resolve.
COPY pom.xml /build/pom.xml
COPY storage-service/pom.xml /build/storage-service/pom.xml
COPY file-sync-service/pom.xml /build/file-sync-service/pom.xml
RUN mvn -q -pl storage-service -am dependency:go-offline

COPY storage-service/src /build/storage-service/src
RUN mvn -q -pl storage-service -am package -DskipTests

# ── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/storage-service/target/storage-service-*.jar /app/app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:+UseG1GC", "-Xmx200m", "-jar", "/app/app.jar"]
