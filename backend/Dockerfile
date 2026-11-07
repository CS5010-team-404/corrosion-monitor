# Team backend (auth, analytics, alerts, audit, reports, admin). Build context: repository root, because the backend
# inherits the root pom.
#   docker build -f backend/Dockerfile -t corrosion-watch-api .
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# The root pom lists every module, so the reactor needs simulator/pom.xml too (its sources are not built).
COPY pom.xml pom.xml
COPY backend/pom.xml backend/pom.xml
COPY simulator/pom.xml simulator/pom.xml
RUN mvn -q -B -pl backend -am dependency:go-offline || true
COPY backend/src backend/src
RUN mvn -q -B -pl backend -am -DskipTests package

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/backend/target/backend-*.jar app.jar
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
