Read render.yaml and docs/DEPLOYMENT.md.

1. Reconcile render.yaml with reality: the backend's Dockerfile path and context, its health check path, the env
   var names it actually reads (database, JWT key), and the exact name of any backend service already on Render.
   The JWT key must be shared through the corrosion-watch-shared env group.
2. If the backend has no Dockerfile, propose one in deploy/backend.Dockerfile (multi-stage Maven or Gradle build,
   Java 21 JRE) and show it to me before committing; do not move backend files.
3. Make sure both CI workflows pass and that changes in frontend/ do not rebuild the Java services.
4. After I create the Blueprint on Render and fill the variables, run scripts/smoke-test.sh against the three URLs,
   send a signed reading with scripts/send-reading.sh, and fix whatever fails. Update docs/DEPLOYMENT.md with
   anything that differed. Stop with the three live URLs and the smoke-test output.
