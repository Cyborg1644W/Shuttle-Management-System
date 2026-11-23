# Backend-only build: the Android SDK, Gradle caches and local.properties are not used.
# Temurin 21.0.13+9 multi-platform image digests, resolved from Docker Hub.
FROM eclipse-temurin:21-jdk-jammy@sha256:432e54724c3e57d1846aeb886f4d339f3b0522931c4e64119c2797013b7cfc3c AS build
WORKDIR /build
COPY common/src/main/java/ common/
COPY server-backend/src/main/java/ server/
RUN find common server -name '*.java' -print | sort > sources.txt \
    && javac --release 17 -encoding UTF-8 -d classes @sources.txt \
    && jar --create --file shuttle-server.jar --main-class com.shuttle.server.ServerMain -C classes .

FROM eclipse-temurin:21-jre-jammy@sha256:dff6eb46527bd293a3c06bbbe9e64e8c9f91f01905b5abbdad31761c03059d2c
RUN groupadd --system --gid 10001 shuttle \
    && useradd --system --uid 10001 --gid shuttle --home-dir /app --no-create-home shuttle \
    && mkdir -p /app/data \
    && chown shuttle:shuttle /app/data
COPY --from=build /build/shuttle-server.jar /app/shuttle-server.jar
WORKDIR /app/data
ENV PORT=8080 \
    DATA_DIR=/app/data \
    STORAGE_MODE=local \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/shuttle-server.jar"]
