# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the whole multi-module project so the parent pom + sibling modules resolve.
COPY pom.xml /build/pom.xml
COPY file-sync-service/pom.xml /build/file-sync-service/pom.xml
COPY storage-service/pom.xml /build/storage-service/pom.xml
RUN mvn -q -pl file-sync-service -am dependency:go-offline

COPY file-sync-service/src /build/file-sync-service/src
RUN mvn -q -pl file-sync-service -am package -DskipTests

# ── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/file-sync-service/target/file-sync-service-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseG1GC", "-jar", "/app/app.jar"]
