# syntax=docker/dockerfile:1
#
# Every image compose runs, from one file. The reactor is built once, inside the image, so that
# `docker compose up` on a clean clone never copies a jar nobody built; each service's runtime stage
# then takes its own jar, and each migration stage its own database's SQL
# (docs/research/grpc-and-multi-module-layout.md section 8).

# -- the build, once ------------------------------------------------------------------------------
# A glibc image: the protobuf plugin runs protoc and the gRPC code generator as native binaries.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# A Windows checkout may carry CRLF in the wrapper script; the JVM does not care, sh does.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests -DskipITs \
        -pl nostro-api-service,nostro-outbox-relay,nostro-projection-service -am package

# -- the three deployables (ADR-0009) -------------------------------------------------------------
FROM eclipse-temurin:21-jre AS api
WORKDIR /app
COPY --from=build /src/nostro-api-service/target/nostro-api-service-*.jar app.jar
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM eclipse-temurin:21-jre AS relay
WORKDIR /app
COPY --from=build /src/nostro-outbox-relay/target/nostro-outbox-relay-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM eclipse-temurin:21-jre AS projection
WORKDIR /app
COPY --from=build /src/nostro-projection-service/target/nostro-projection-service-*.jar app.jar
EXPOSE 9090 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# -- migrations, exactly once per database --------------------------------------------------------
# The Flyway release Spring Boot 4.1.1 manages, so the history table the services validate against
# is written by the same Flyway that would have written it in-process.
FROM flyway/flyway:12.4.0-alpine AS ledger-migrate
COPY nostro-ledger-schema/src/main/resources/db/migration /flyway/sql

FROM flyway/flyway:12.4.0-alpine AS projection-migrate
COPY nostro-projection-service/src/main/resources/db/migration /flyway/sql
