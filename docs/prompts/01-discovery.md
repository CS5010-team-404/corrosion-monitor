Read CLAUDE.md, docs/ARCHITECTURE.md and docs/CONTRACTS.md. My teammates wrote the backend in this repository;
I added frontend/, platform/ and the deployment files. This step is READ ONLY: do not create, edit or delete
anything except the two documents named below.

1. Map the backend: build tool and modules, Java and Spring Boot versions, base package, Dockerfile, how the schema
   is created (Flyway, Liquibase, JPA ddl-auto), existing tables, tests, CI, existing deployment config.
2. API inventory: every endpoint with method, path, request and response shape, required role and error codes.
3. Authentication: login endpoint and response, password hashing, lockout, JWT algorithm and the env var holding
   the key, claim names (subject, roles), token lifetime. WebSocket/STOMP endpoint and topics, if any.
4. Run the backend's build and tests and report the result. Do not fix anything.
5. Compare what exists with what the UI needs: frontend/src/api/endpoints.ts and types.ts, and the shared tables
   in platform/ecp-platform-app/src/main/resources/db/platform/V1__platform_tables.sql. Note table-name clashes
   and existing tables that could replace a platform table.
6. Write docs/REPO_MAP.md (structure, how to run, auth details) and docs/GAPS.md (one line per missing or
   mismatched item: what, which endpoint or table, owner: Aayushee analytics, Dumebi alerts, Anela audit,
   Joshua platform/frontend, or team for auth and config).

Then stop and summarise: the top risks for integration, and the decisions you need from me.
