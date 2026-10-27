# Asimetría — Sistema de Inversión y Análisis Bursátil

Plataforma que detecta **comportamientos asimétricos** de acciones frente al NASDAQ 100. El caso de referencia es RiTech: si el índice sube 2 %, RiTech sube 4 % (β alcista 2.0); si el índice cae 2 %, RiTech solo cae 2.5 % (β bajista 1.25). A partir de ahí recomienda estrategias según el perfil de riesgo, simula inversiones pasadas (*backtesting*) y emite alertas.

Proyecto de *Calidad de Software — Momento 2*, Universidad Cooperativa de Colombia, Campus Pasto.

## Arquitectura

Sigue el diagrama de componentes del documento:

```
 Navegador ──HTTP──▶ Nginx (web estática + proxy /api, CSP, gzip)
                        │
                        ▼
                  FastAPI (API REST /api/v1, JWT, OpenAPI 3) ──▶ Motor de cálculo (NumPy)
                     │            │
                     ▼            ▼
   PostgreSQL + TimescaleDB    Redis (caché de métricas/recomendaciones, cotizaciones en vivo, broker)
                     ▲            ▲
                     └── Celery worker + beat ──▶ Proveedor bursátil (simulado | Alpha Vantage)
                         (cotizaciones c/5 s, alertas c/60 s, cierre diario, purga de auditoría)        └▶ SendGrid
```

## Estructura del monorepo

```
.
├── apps/
│   ├── api/                      # Backend Python (FastAPI + SQLAlchemy + Celery)
│   │   ├── app/
│   │   │   ├── main.py           # App, /health, cabeceras de seguridad, manejo de errores
│   │   │   ├── config.py         # Configuración por variables de entorno
│   │   │   ├── db.py · models.py # Motor, sesión, modelos ORM (espejo de schema.sql)
│   │   │   ├── security.py       # bcrypt, JWT HS256, dependencia 401
│   │   │   ├── calculos.py       # Beta alcista/bajista, volatilidad, CAPM, backtesting, recomendaciones
│   │   │   ├── proveedores.py    # Proveedor simulado / Alpha Vantage + reintentos y circuit breaker
│   │   │   ├── servicios.py      # Carga inicial, métricas, cotizaciones, cierre diario, alertas
│   │   │   ├── cache.py · auditoria.py · schemas.py · worker.py
│   │   │   └── routers/          # auth.py · mercado.py · usuario.py
│   │   ├── tests/                # 68 pruebas (pytest), cobertura ≥ 80 % obligatoria
│   │   └── Dockerfile
│   └── web/                      # Frontend HTML + CSS + JS sin dependencias (9 vistas de los mockups)
│       ├── nginx.conf            # Proxy inverso, CSP y cabeceras de seguridad
│       ├── Dockerfile            # Imagen Nginx con las páginas incluidas
│       └── assets/js/            # ui.js (sesión, cliente API) · market.js (capa de datos) · pages/*.js
├── packages/db/
│   ├── schema.sql                # DDL PostgreSQL/TimescaleDB (lo ejecuta el contenedor al iniciar)
│   └── benchmark_rnf02.sql       # Prueba de capacidad: 1 M de filas
├── docker-compose.yml            # db · redis · api · worker · beat · web
├── docker-compose.prod.yml       # Producción: imágenes de GHCR + Caddy (HTTPS) + backups + logs rotados
├── deploy/                       # Caddyfile · desplegar.sh · preparar-servidor.sh
├── docs/ISO-25010.md             # Aplicación de la norma: dónde, objetivo, costo operativo y evidencia
└── .github/workflows/           # ci.yml (pruebas, cobertura, builds) · deploy.yml (GHCR + SSH)
```

## Producción

Publicación con dominio propio, HTTPS (TLS 1.3, HTTP/3), despliegue automático desde GitHub Actions, backups diarios y ruta de escalabilidad: ver **[docs/DESPLIEGUE.md](docs/DESPLIEGUE.md)**.

## Ejecutar (RNF-10: un solo comando)

Requisito: Docker con Docker Compose.

1. Copia `.env.example` a `.env` y cambia `POSTGRES_PASSWORD` y `JWT_SECRET`.
2. Levanta la pila:

```bash
docker compose up -d
```

Al terminar, abre:

- Web: <http://localhost:8080>. Usa **«Ver demo en vivo»** o crea una cuenta.
- Documentación de la API (Swagger, OpenAPI): <http://localhost:8080/docs>
- Estado del sistema: <http://localhost:8080/health>

Las páginas van dentro de la imagen de Nginx. Si editas `apps/web`, publica los cambios con `docker compose up -d --build web`.

En el primer arranque la API crea el esquema y carga 5 años de historial (≈ 14.000 cotizaciones). Desde cero, con descarga de imágenes, tardó **4 min 08 s** en el equipo de prueba; con las imágenes ya descargadas, unos 26 s.

### Datos reales

Por defecto `PROVEEDOR=simulado`: datos deterministas con el caso RiTech incluido, sin necesidad de API key. Para usar datos reales, define `PROVEEDOR=alphavantage` y `ALPHAVANTAGE_API_KEY` en `.env`. El plan gratuito permite 5 peticiones por minuto, así que sube también `INTERVALO_COTIZACIONES` a 60 o más.

### Desarrollo y pruebas del backend

```bash
cd apps/api && python -m venv .venv && .venv/Scripts/pip install -r requirements-dev.txt
```

```bash
cd apps/api && .venv/Scripts/python -m pytest
```

Para desarrollar sin Docker, con SQLite, caché en memoria y las tareas dentro del mismo proceso:

```bash
cd apps/api && SERVIR_WEB=1 TAREAS_EN_API=1 .venv/Scripts/python -m uvicorn app.main:app --port 8000
```

## Qué probar

| Criterio | Cómo verlo |
|---|---|
| HU-03 β alcista 2.0 / bajista 1.25 | Análisis de RiTech → «Desglose»: +2 % → +4 % (2.00×), −2 % → −2.5 % (1.25×) |
| HU-03 CA-4 datos insuficientes | Análisis → *Helios Renovables* (12 sesiones) |
| HU-05 1.000 USD → 1.040 / 975 | Portafolio → «Día alcista (+2 %)» / «Día bajista (−2 %)» |
| HU-04 CA-5 sin perfil | Crear cuenta sin perfil → Estrategias pide el quiz (409) |
| HU-07 CA-4 / bloqueo | Contraseña errónea → «Credenciales inválidas»; tras 5 intentos → 429 durante 60 s |
| RNF-03 401 | `curl -i localhost:8080/api/v1/metrics/` → 401 |
| RNF-06 proveedor caído | Configuración → «Proveedor bursátil caído» → Mercados muestra «Datos desactualizados» con el último precio |
| HU-06 CA-4 correo caído | Configuración → «Servicio de correo caído» → Alertas → la regla queda «reintentando envío» |
| HU-10 CA-5 backend caído | `docker compose stop api` → Estrategias muestra el error y **Reintentar**; la web sigue en línea |
| RNF-04 auditoría | Configuración → Registro de auditoría (JSON con IP, UTC y estado) · `docker compose logs api` |
| RNF-01 rendimiento | Ver la sección 3 de `docs/ISO-25010.md` (prueba con ApacheBench) |
| RNF-02 capacidad | `docker compose exec -T db psql -U asimetria -d asimetria < packages/db/benchmark_rnf02.sql` |

## Equipo

Oscar Felipe Hernández Velasco · Ian Santiago Ordóñez Rosero · Cristian Alexis Jiménez Bastidas · William Eduardo Cando Cuarán · Jair Alfonso Parrado Calderón — Magíster Aida Liliana Rosero Ruiz.
