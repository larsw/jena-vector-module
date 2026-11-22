# syntax=docker/dockerfile:1
#
# Fuseki with jena-vector and the Qdrant backend.
#
#   docker build -t jena-vector-fuseki .
#   docker compose up            # Fuseki and Qdrant together, see compose.yaml
#
# Configuration comes from environment variables (see docker/entrypoint.sh and the README), or from your own
# file: mount it and set FUSEKI_CONFIG to its path. TDB2 databases live in the /fuseki/databases volume.

ARG JAVA_VERSION=21

## Build: compile the modules and collect Fuseki's runtime classpath into jena-vector-fuseki/target/lib.
## Tests are skipped here: the Qdrant tests start containers, which a build stage cannot. Run them with
## ./mvnw install on a machine with Docker.
FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS build
WORKDIR /src
COPY pom.xml ./
COPY jena-vector/pom.xml jena-vector/
COPY jena-vector-qdrant/pom.xml jena-vector-qdrant/
COPY jena-vector-fuseki/pom.xml jena-vector-fuseki/
COPY jena-vector/src jena-vector/src
COPY jena-vector-qdrant/src jena-vector-qdrant/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

## Runtime: a JRE, the jars, and the entrypoint. No build tools, runs as a non-root user.
FROM eclipse-temurin:${JAVA_VERSION}-jre

RUN groupadd --system --gid 10001 fuseki \
 && useradd --system --uid 10001 --gid fuseki --home-dir /fuseki --no-create-home fuseki \
 && mkdir -p /fuseki/databases \
 && chown -R fuseki:fuseki /fuseki

COPY --from=build /src/jena-vector-fuseki/target/lib/ /opt/fuseki/lib/
COPY docker/config.ttl.template /opt/fuseki/config.ttl.template
COPY --chmod=0755 docker/entrypoint.sh /opt/fuseki/entrypoint.sh

USER fuseki
WORKDIR /fuseki
VOLUME /fuseki/databases
EXPOSE 3030

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75" \
    DATASET_NAME=docs \
    QDRANT_HOST=qdrant \
    QDRANT_PORT=6334 \
    VECTOR_DIMENSION=384 \
    VECTOR_METRIC=cosine \
    VECTOR_PREDICATES=http://example/embedding

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${FUSEKI_PORT:-3030}"]

ENTRYPOINT ["/opt/fuseki/entrypoint.sh"]
