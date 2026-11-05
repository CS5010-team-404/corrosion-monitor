#!/usr/bin/env bash
# Runs the backend's Flyway migrations once against a database (meant for the production Aiven MySQL) and prints the
# applied versions and the tables. Credentials come from the environment only and the password is never printed:
#
#   DB_URL='jdbc:mysql://HOST:PORT/defaultdb?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' \
#   MYSQL_USER=avnadmin MYSQL_PASSWORD='...' scripts/check-aiven.sh
#
# Tip: start the line with a space (and HISTCONTROL=ignorespace) to keep the password out of shell history.
# Builds the backend jar first (SKIP_BUILD=1 to reuse backend/target). Needs Java 17 and Maven. See docs/DEPLOYMENT.md.
set -euo pipefail
: "${DB_URL:?set DB_URL (jdbc:mysql://...)}"
: "${MYSQL_USER:?set MYSQL_USER}"
: "${MYSQL_PASSWORD:?set MYSQL_PASSWORD}"
case "$DB_URL" in
  *[Pp]assword=*) echo "Put the password in MYSQL_PASSWORD, not in DB_URL." >&2; exit 2 ;;
esac

cd "$(dirname "$0")/.."
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "Building the backend jar..."
  mvn -q -B -pl backend -am -DskipTests package
fi
JAR="$(ls backend/target/backend-*.jar | grep -v '\.original$' | head -n 1)"

echo "Database: ${DB_URL%%\?*}"
echo "User:     ${MYSQL_USER}"
# DB_URL, MYSQL_USER and MYSQL_PASSWORD reach MigrateOnly through the environment, not the command line.
exec java -cp "$JAR" -Dloader.main=com.team404.corrosion.tools.MigrateOnly \
  org.springframework.boot.loader.launch.PropertiesLauncher
