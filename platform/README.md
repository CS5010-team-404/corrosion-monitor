# Data platform

Ingestion, sensor health, simulator and forecaster for Corrosion Watch. See `../docs/ARCHITECTURE.md`.

```
mvn -B verify                                   # build and test all modules
docker compose -f ../docker-compose.platform.yml up --build
ECP_PLATFORM_AUTH=none mvn -pl ecp-platform-app -am spring-boot:run   # needs a local MySQL "ecp"
mvn -q -pl ecp-forecast -am exec:java -Dexec.args="--out ecp-forecast/src/main/resources/models"   # retrain
```
Environment variables are listed in `ecp-platform-app/src/main/resources/application.yml`.
