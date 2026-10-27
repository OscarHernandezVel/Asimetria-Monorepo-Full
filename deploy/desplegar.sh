#!/bin/sh
# Despliega (o revierte) una versión en el servidor. Lo ejecuta GitHub Actions por SSH en cada push a main.
#   TAG=<sha> ./deploy/desplegar.sh      → versión concreta (rollback: usar el sha anterior)
#   ./deploy/desplegar.sh                → latest
set -eu
cd "$(dirname "$0")/.."
export TAG="${TAG:-latest}"
C="docker compose -f docker-compose.yml -f docker-compose.prod.yml"

echo "→ Descargando imágenes $TAG"
$C pull api worker beat web
echo "→ Aplicando (solo se recrean los servicios que cambiaron)"
$C up -d --remove-orphans

echo "→ Esperando a que la API esté sana"
i=0
until $C exec -T api python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)" 2>/dev/null; do
  i=$((i + 1))
  [ "$i" -ge 30 ] && { echo "✗ La API no respondió en 2 min. Revisa: $C logs api"; exit 1; }
  sleep 4
done
docker image prune -f >/dev/null
echo "✓ Desplegado $TAG"
