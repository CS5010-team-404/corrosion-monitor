# Data platform service (ingestion, sensor health, simulator, forecast). Build context: repository root.
#   docker build -f deploy/platform.Dockerfile -t corrosion-watch-platform .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY platform/pom.xml platform/pom.xml
COPY platform/ecp-common/pom.xml platform/ecp-common/pom.xml
COPY platform/ecp-simulator/pom.xml platform/ecp-simulator/pom.xml
COPY platform/ecp-forecast/pom.xml platform/ecp-forecast/pom.xml
COPY platform/ecp-ingestion/pom.xml platform/ecp-ingestion/pom.xml
COPY platform/ecp-health/pom.xml platform/ecp-health/pom.xml
COPY platform/ecp-platform-app/pom.xml platform/ecp-platform-app/pom.xml
RUN mvn -q -B -f platform/pom.xml dependency:go-offline || true
COPY platform platform
RUN mvn -q -B -f platform/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/platform/ecp-platform-app/target/ecp-platform.jar app.jar
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD wget -qO- http://localhost:${PORT:-8081}/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
