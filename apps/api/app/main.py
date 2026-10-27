"""API REST de Asimetría (FastAPI). Documentación OpenAPI 3 en /docs (RNF-07)."""
import asyncio
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import text
from sqlalchemy.exc import TimeoutError as SATimeout

from . import cache, servicios
from .config import settings
from .db import SessionLocal, bloqueo_arranque, engine, init_db
from .routers import auth, integraciones, mercado, usuario

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
# httpx registra la URL completa de cada petición, y Alpha Vantage exige la API key en la URL (?apikey=…):
# a nivel INFO la clave terminaría en los logs (HU-01 CA-1). Solo se registran advertencias y errores.
for _ruidoso in ("httpx", "httpcore"):
    logging.getLogger(_ruidoso).setLevel(logging.WARNING)
log = logging.getLogger("asimetria")
WEB = Path(__file__).resolve().parents[2] / "web"


async def _tareas_en_proceso():
    """Modo sin Celery (TAREAS_EN_API=1): mismas tareas que el beat, en el proceso de la API."""
    def tick(fn):
        with SessionLocal() as db:
            return fn(db)
    n = 0
    while True:
        try:
            await asyncio.to_thread(tick, servicios.sincronizar_cotizaciones)
            if n % max(1, 60 // settings.intervalo_cotizaciones) == 0:
                await asyncio.to_thread(tick, servicios.evaluar_alertas)
        except Exception:  # noqa: BLE001 — una tarea fallida no debe tumbar la API
            log.exception("tarea en proceso falló")
        n += 1
        await asyncio.sleep(settings.intervalo_cotizaciones)


@asynccontextmanager
async def lifespan(_: FastAPI):
    with bloqueo_arranque():
        init_db()
        with SessionLocal() as db:
            servicios.carga_inicial(db)
    tarea = asyncio.create_task(_tareas_en_proceso()) if settings.tareas_en_api else None
    yield
    if tarea:
        tarea.cancel()


app = FastAPI(title="Asimetría API", version="1.0.0", lifespan=lifespan,
              description="Sistema de Inversión y Análisis Bursátil: beta alcista/bajista, recomendaciones, "
                          "backtesting, alertas y listas de seguimiento.")
if settings.cors_origenes:
    app.add_middleware(CORSMiddleware, allow_origins=settings.cors_origenes, allow_methods=["*"],
                       allow_headers=["Authorization", "Content-Type"])


CABECERAS = {"X-Content-Type-Options": "nosniff", "Referrer-Policy": "same-origin", "X-Frame-Options": "DENY"}


@app.middleware("http")
async def cabeceras_seguridad(request: Request, call_next):
    r = await call_next(request)
    for k, v in CABECERAS.items():
        r.headers.setdefault(k, v)
    if request.url.path.startswith("/api/"):
        r.headers.setdefault("Cache-Control", "no-store")
    return r


@app.exception_handler(SATimeout)
async def bd_saturada(request: Request, exc: SATimeout):
    """Pool de conexiones agotado: 503 inmediato con Retry-After en lugar de colgar la petición."""
    log.warning("Pool de BD agotado en %s", request.url.path)
    return JSONResponse({"detail": "El servicio está ocupado. Inténtalo de nuevo en unos segundos."}, status_code=503,
                        headers={**CABECERAS, "Retry-After": "2"})


@app.exception_handler(Exception)
async def error_no_controlado(request: Request, exc: Exception):
    """Ningún detalle interno llega al cliente; el error queda en el log del servidor."""
    log.exception("Error no controlado en %s", request.url.path)
    return JSONResponse({"detail": "Error interno. Inténtalo de nuevo en unos segundos."}, status_code=500,
                        headers=CABECERAS)  # este handler corre fuera del middleware


for r in (auth.router, mercado.router, usuario.router, integraciones.router):
    app.include_router(r, prefix="/api/v1")


@app.get("/health", tags=["Operación"])
def health():
    """Sonda de salud para el monitor externo (RNF-05): verifica BD y caché."""
    try:
        with engine.connect() as c:
            c.execute(text("SELECT 1"))
        db_ok = True
    except Exception:  # noqa: BLE001
        db_ok = False
    cache_ok = cache.ping()
    cuerpo = {"estado": "ok" if db_ok and cache_ok else "degradado", "db": db_ok, "cache": cache_ok, "cache_tipo": cache.TIPO}
    return JSONResponse(cuerpo, status_code=200 if db_ok else 503)


if settings.servir_web and WEB.is_dir():  # desarrollo: un solo origen para web y API
    app.mount("/", StaticFiles(directory=WEB, html=True), name="web")
