# ADR 0012: PostgreSQL, one database per service, one local container

Status: accepted (plan decision D12)

## Context
Each service must own its data, and the local stack should be as small as it can be.

## Decision
One Postgres 17 container holds two databases, `claims` and `insights`, each with its own role. Flyway migrates each service's database on start. The init script creates both databases before either service starts, and the container's healthcheck waits for both.

## Consequences
- Neither service can read the other's tables by accident: the roles and connection URLs are separate (`SPRING_DATASOURCE_URL` in `docker-compose.yml`).
- Costs: one local instance is a shortcut, and a Postgres outage stops both services. Because the databases are separate, production can put them on separate servers without code changes.
- The claims application role also runs migrations locally; in production the service should run as a non-owner (see ADR 0008).
- Migrations were edited in place during the work, so a volume created by an earlier build fails Flyway's checksum. Reset with `docker compose down -v`.

## Alternatives rejected
- **A shared database.** One system with extra steps.

## Where it is in the code
- `infra/postgres/init-databases.sql`; `docker-compose.yml:14-31` (postgres, healthcheck on both databases), `:95` and `:119` (separate datasource URLs).
- Migrations: `services/claims-service/src/main/resources/db/migration/V1` to `V8`, `services/insights-service/src/main/resources/db/migration/V1` to `V4`. Tests: `MigrationTest`, `MigrationUpgradeTest`.
