#!/usr/bin/env bash
# Mide el efecto del caché Redis del Gateway (Lab 4, requisito 5.3): tiempos con y sin caché sobre N peticiones
# y el hit ratio, leído de la cabecera X-Gateway-Cache (X-Cache la reemplaza CloudFront).
#
# Uso:
#   GATEWAY_URL=https://<gateway> CACHE_USER=admin CACHE_PASS='...' ./scripts/medir-cache.sh
# Variables opcionales:
#   N=50                                    peticiones por escenario
#   RUTA='/api/materias/cursos?pageNumber=1&pageSize=20&sortBy=id'   consulta a medir
#   SALIDA=medicion-cache.csv               detalle de cada petición
#
# "Sin caché" agrega un parámetro distinto en cada petición (_nc=<n>): la clave de Redis cambia, así que todas
# son MISS y el módulo responde siempre. "Con caché" repite exactamente la misma consulta.
set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
CACHE_USER="${CACHE_USER:-admin}"
N="${N:-50}"
RUTA="${RUTA:-/api/materias/cursos?pageNumber=1&pageSize=20&sortBy=id}"
SALIDA="${SALIDA:-medicion-cache.csv}"

if [ -z "${CACHE_PASS:-}" ]; then
  read -rsp "Contraseña de $CACHE_USER: " CACHE_PASS
  echo
fi

TOKEN=$(curl -sf -X POST "$GATEWAY_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$CACHE_USER\",\"password\":\"$CACHE_PASS\"}" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
if [ -z "$TOKEN" ]; then
  echo "No se pudo iniciar sesión en $GATEWAY_URL" >&2
  exit 1
fi

SEP='&'
case "$RUTA" in *\?*) ;; *) SEP='?' ;; esac
CABECERAS=$(mktemp)
trap 'rm -f "$CABECERAS"' EXIT

# Imprime una línea por petición: escenario,n,http,segundos,X-Gateway-Cache
medir() {
  local escenario=$1 i url tiempo cache
  for i in $(seq 1 "$N"); do
    url="$GATEWAY_URL$RUTA"
    [ "$escenario" = sin_cache ] && url="$url${SEP}_nc=$$-$i"
    tiempo=$(curl -s -o /dev/null -D "$CABECERAS" -w '%{http_code},%{time_total}' \
      -H "Authorization: Bearer $TOKEN" "$url")
    cache=$(grep -i '^x-gateway-cache:' "$CABECERAS" | awk '{print $2}' | tr -d '\r')
    echo "$escenario,$i,$tiempo,${cache:-NONE}"
  done
}

resumen() {
  sort -t, -k4 -n | awk -F, -v nombre="$1" '
    { t[NR] = $4 * 1000; s += t[NR]; if ($5 == "HIT") h++; if ($3 != 200) e++ }
    END {
      printf "| %-9s | %4d | %8.0f | %6.0f | %6.0f | %6.0f | %6.0f | %3d/%d (%.0f %%) | %d |\n",
        nombre, NR, s / NR, t[int((NR + 1) * 0.50)], t[int((NR + 1) * 0.95)], t[1], t[NR], h, NR, h / NR * 100, e
    }'
}

echo "escenario,n,http,segundos,x_gateway_cache" > "$SALIDA"
echo "Midiendo $N peticiones por escenario contra $GATEWAY_URL$RUTA ..."
medir sin_cache | tee -a "$SALIDA" > /dev/null
medir con_cache | tee -a "$SALIDA" > /dev/null

echo
echo "| Escenario | N    | Promedio | p50 ms | p95 ms | Mín ms | Máx ms | Hit ratio      | Errores |"
echo "|-----------|------|----------|--------|--------|--------|--------|----------------|---------|"
grep '^sin_cache,' "$SALIDA" | resumen "Sin caché"
grep '^con_cache,' "$SALIDA" | resumen "Con caché"
echo
echo "Detalle por petición: $SALIDA"
