#!/usr/bin/env bash
# Offline retrieval tooling: fill the pgvector index, inspect its size, and run the annotated
# retrieval evaluation. Manual tool only: it is not wired into Maven, the application build, or CI.
#
#   tools/retrieval-tooling.sh build [--table all|corpus|grounding] [--refresh] [--limit N]
#   tools/retrieval-tooling.sh stats
#   tools/retrieval-tooling.sh checksum
#   tools/retrieval-tooling.sh eval [--thresholds 0.2,0.3] [--per-category 1,2,3]
#
# The PostgreSQL JDBC driver is only needed on this script's classpath. Override discovery with
# WRITELAB_PG_JDBC_JAR=/path/to/postgresql.jar when the driver lives elsewhere.
set -euo pipefail

cd "$(dirname "$0")/.."

jar="${WRITELAB_PG_JDBC_JAR:-}"
if [ -z "$jar" ]; then
  for directory in \
    "$HOME/.m2/repository/org/postgresql/postgresql" \
    "$HOME/.cache/sandcastle/m2/org/postgresql/postgresql" \
    /home/*/.m2/repository/org/postgresql/postgresql \
    /home/*/.cache/sandcastle/m2/org/postgresql/postgresql \
    /root/.m2/repository/org/postgresql/postgresql; do
    [ -d "$directory" ] || continue
    candidate="$(find "$directory" -name 'postgresql-*.jar' 2>/dev/null | sort -V | tail -1 || true)"
    if [ -n "$candidate" ]; then
      jar="$candidate"
      break
    fi
  done
fi

if [ -z "$jar" ] || [ ! -f "$jar" ]; then
  echo "找不到 PostgreSQL JDBC 驱动；请设置 WRITELAB_PG_JDBC_JAR=/path/to/postgresql.jar" >&2
  exit 1
fi

exec java -cp "$jar" tools/RetrievalTooling.java "$@"
