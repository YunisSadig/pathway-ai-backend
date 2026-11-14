# Production image of the Pathway AI API. Build: docker build -t pathway-ai-api .   Run needs DB_URL, DB_USERNAME, DB_PASSWORD and
# STAGING_ACCESS_KEY (or PUBLIC_LAUNCH=true), CORS_ALLOWED_ORIGINS; see docs/deployment-api.md. Nothing local to a laptop is used.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests \
 && cp "$(ls target/pathway-ai-backend-*.jar | grep -v -- '-plain' | head -1)" /app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app -H -s /sbin/nologin app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER app
# Small free instances: heap as a share of the container limit, serial GC, fast JIT warm-up, small thread stacks.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${PORT:-8080}/actuator/health/readiness" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
