# ADR 0001: Java 21 and Spring Boot

Status: accepted (plan decision D1)

## Context
Both services need HTTP APIs, JPA/SQL, Kafka and JWT support. Java 21 and Maven are the toolchain already on the development machine; `dotnet` is not.

## Decision
Both services are Spring Boot 4.1.2 on Java 21, built with Maven. The root `pom.xml` is only an aggregator; each service builds on its own with `spring-boot-starter-parent` as its parent.

## Consequences
- One language and one build tool for both backends. Contract tests, ArchUnit rules and Testcontainers all run in `mvn verify`.
- `mvn verify` needs a running Docker daemon, because the integration tests start Postgres and Kafka containers.
- Each service is capped at 768 MB in the compose file (`mem_limit`), which is a real cost on a small laptop.

## Alternatives rejected
- **.NET.** Equally acceptable for this problem, but it adds a toolchain install before anyone can run the code.

## Where it is in the code
- `pom.xml:14-15` (two modules); `services/claims-service/pom.xml:19` (`java.version` 21).
