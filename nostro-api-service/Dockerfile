# Builds the API service inside the image, so `docker compose up` never copies a jar nobody built.
# Build context is the repository root (see compose.yaml): the reactor is built with -am so the
# library modules come along.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY nostro-domain nostro-domain
COPY nostro-ledger-schema nostro-ledger-schema
COPY nostro-outbox nostro-outbox
COPY nostro-outbox-relay nostro-outbox-relay
COPY nostro-projection-service nostro-projection-service
COPY nostro-persistence nostro-persistence
COPY nostro-test-support nostro-test-support
COPY nostro-api-service nostro-api-service
# A Windows checkout may carry CRLF in the wrapper script; the JVM does not care, sh does.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests -pl nostro-api-service -am package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/nostro-api-service/target/nostro-api-service-*.jar nostro-api.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/nostro-api.jar"]
