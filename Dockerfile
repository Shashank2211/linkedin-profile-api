# ---- build ------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer so source edits don't re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# Spring Boot layered jars: dependencies change rarely, application code changes every
# push. Extracting them into separate layers turns a redeploy into a few hundred KB.
RUN java -Djarmode=layertools -jar target/linkedin-profile-api-1.0.0.jar extract --destination layers

# ---- runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# Playwright is deliberately not installed. The browser fallback is an optional Spring
# profile, and bundling Chromium here would take the image from ~180MB to ~700MB for a
# code path that runs on a small minority of requests.
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

COPY --from=build /build/layers/dependencies/ ./
COPY --from=build /build/layers/spring-boot-loader/ ./
COPY --from=build /build/layers/snapshot-dependencies/ ./
COPY --from=build /build/layers/application/ ./

RUN chown -R app:app /app
USER app

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: free tiers change the memory ceiling and a
# hardcoded heap is how a container gets OOM-killed after a plan change.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
