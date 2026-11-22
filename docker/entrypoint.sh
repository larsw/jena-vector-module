#!/bin/sh
# Starts Fuseki with jena-vector. Renders the configuration from environment variables unless FUSEKI_CONFIG
# names a file to use instead. Extra arguments go to Fuseki (for example --verbose).
set -eu

fail() { echo "entrypoint: $*" >&2; exit 64; }

# Reject values that would break out of the Turtle they are written into.
plain() {   # name value pattern
    printf '%s' "$2" | grep -Eqx "$3" || fail "$1='$2' is not valid"
    printf '%s' "$2"
}

render() {
    : "${DATASET_NAME:=docs}" "${QDRANT_HOST:=qdrant}" "${QDRANT_PORT:=6334}" "${QDRANT_TLS:=false}"
    : "${QDRANT_COLLECTION:=$DATASET_NAME}" "${VECTOR_DIMENSION:=384}" "${VECTOR_METRIC:=cosine}"
    : "${VECTOR_PREDICATES:=http://example/embedding}"

    name=$(plain DATASET_NAME "$DATASET_NAME" '[A-Za-z0-9_-]+')
    host=$(plain QDRANT_HOST "$QDRANT_HOST" '[A-Za-z0-9.:_-]+')
    port=$(plain QDRANT_PORT "$QDRANT_PORT" '[0-9]+')
    tls=$(plain QDRANT_TLS "$QDRANT_TLS" 'true|false')
    collection=$(plain QDRANT_COLLECTION "$QDRANT_COLLECTION" '[A-Za-z0-9_-]+')
    dimension=$(plain VECTOR_DIMENSION "$VECTOR_DIMENSION" '[0-9]+')
    metric=$(plain VECTOR_METRIC "$VECTOR_METRIC" 'cosine|dot|euclidean')

    predicates=""
    for p in $(echo "$VECTOR_PREDICATES" | tr ',' ' '); do
        p=$(plain VECTOR_PREDICATES "$p" '[A-Za-z][A-Za-z0-9+.-]*:[^<>" {}|\\^`]+')
        predicates="${predicates:+$predicates, }<$p>"
    done
    [ -n "$predicates" ] || fail "VECTOR_PREDICATES is empty"
    predicates=$(printf '%s' "$predicates" | sed 's/&/\\&/g')   # & is special in a sed replacement

    # The key itself never goes into the file: the index reads it from this variable.
    apikey="## no API key"
    [ -n "${QDRANT_API_KEY:-}" ] && apikey='vec:apiKeyEnv  "QDRANT_API_KEY" ;'

    sed -e "s|@DATASET_NAME@|$name|g" -e "s|@QDRANT_HOST@|$host|" -e "s|@QDRANT_PORT@|$port|" \
        -e "s|@QDRANT_TLS@|$tls|" -e "s|@QDRANT_API_KEY_LINE@|$apikey|" \
        -e "s|@QDRANT_COLLECTION@|$collection|" -e "s|@VECTOR_DIMENSION@|$dimension|" \
        -e "s|@VECTOR_METRIC@|$metric|" -e "s|@VECTOR_PREDICATES@|$predicates|" \
        /opt/fuseki/config.ttl.template > /fuseki/config.ttl
    echo "/fuseki/config.ttl"
}

if [ -n "${FUSEKI_CONFIG:-}" ]; then
    [ -r "$FUSEKI_CONFIG" ] || fail "FUSEKI_CONFIG=$FUSEKI_CONFIG is not a readable file"
    config=$FUSEKI_CONFIG
else
    config=$(render)
fi

# JAVA_OPTS is split into words on purpose.
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -cp '/opt/fuseki/lib/*' org.apache.jena.fuseki.main.cmds.FusekiMainCmd \
    --port="${FUSEKI_PORT:-3030}" --config="$config" "$@"
