"""Mercado, métricas y recomendaciones (HU-01, HU-03, HU-04, HU-09, HU-10)."""
from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import cache, calculos, servicios
from ..config import settings
from ..db import get_db
from ..models import Empresa, Usuario
from ..schemas import CotizacionesOut, EmpresaOut, FallosDemo, MetricaOut, RecomendacionOut, SerieOut
from ..security import usuario_actual

router = APIRouter(tags=["Mercado y métricas"])
protegido = [Depends(usuario_actual)]


def _desde_cache(clave: str) -> Response | None:
    """Si la respuesta está en Redis se envía tal cual (sin json.loads + validación + json.dumps)."""
    raw = cache.get_raw(clave)
    return Response(raw, media_type="application/json") if raw else None


def _empresa(db: Session, ticker: str) -> Empresa:
    e = servicios.empresa(db, ticker.upper())
    if e is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Empresa no encontrada")
    return e


@router.get("/mercado/empresas", response_model=list[EmpresaOut], dependencies=protegido)
def empresas(db: Session = Depends(get_db)):
    return [EmpresaOut(ticker=e.ticker, nombre=e.nombre_empresa, sector=e.sector, es_indice=e.es_indice)
            for e in db.scalars(select(Empresa).where(Empresa.activa).order_by(Empresa.id))]


@router.get("/mercado/precios/{ticker}", response_model=SerieOut, dependencies=protegido)
def precios(ticker: str, dias: int = Query(252, ge=1, le=5000), db: Session = Depends(get_db)):
    e = _empresa(db, ticker)
    f, c = servicios.cierres(db, e.id, dias)
    return SerieOut(ticker=e.ticker, fechas=f, cierres=c)


@router.get("/mercado/cotizaciones", response_model=CotizacionesOut, dependencies=protegido)
def cotizaciones(db: Session = Depends(get_db)):
    """Últimos precios. Si el proveedor no responde, `estado.desactualizado = true` y se entregan los últimos
    datos conocidos (RNF-06): nunca un 500."""
    return servicios.cotizaciones_actuales(db)


@router.get("/metrics/", response_model=list[MetricaOut], dependencies=protegido)
def metricas(db: Session = Depends(get_db)):
    return _desde_cache("metrics:all") or servicios.metricas_listado(db)


@router.get("/metrics/{ticker}", response_model=MetricaOut, dependencies=protegido)
def metrica(ticker: str, db: Session = Depends(get_db)):
    m = next((x for x in servicios.metricas_listado(db) if x["ticker"] == ticker.upper()), None)
    if m is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Empresa no encontrada")
    return m


@router.get("/metrics/{ticker}/analisis", dependencies=protegido)
def analisis(ticker: str, ventana: int = Query(90, ge=10, le=1260), db: Session = Depends(get_db)):
    e = _empresa(db, ticker)
    if e.es_indice:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "Selecciona una empresa, no el índice")
    return {**servicios.analisis(db, e, ventana), "metricas": metrica(e.ticker, db)}


@router.get("/recommendations/", response_model=list[RecomendacionOut])
def recomendaciones(vol_max: float | None = Query(None, gt=0), retorno_min: float | None = None,
                    ratio_min: float | None = None, u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    """HU-04. 409 si el usuario no completó el quiz de perfil (CA-5); lista vacía si nada cumple (CA-4)."""
    clave = f"reco:{u.perfil_riesgo}:{vol_max}:{retorno_min}:{ratio_min}"
    if u.perfil_riesgo and (r := _desde_cache(clave)):
        return r
    try:
        datos = calculos.recomendar(servicios.metricas_listado(db), u.perfil_riesgo, vol_max, retorno_min, ratio_min)
    except PermissionError as e:
        raise HTTPException(status.HTTP_409_CONFLICT, str(e))
    cache.set_json(clave, datos, settings.cache_ttl)
    return datos


@router.get("/destacado")
def destacado(db: Session = Depends(get_db)):
    """Público: tarjeta de la landing (activo con mayor asimetría favorable, 40 sesiones base 100)."""
    datos = cache.get_json("destacado")
    if datos is None:
        ms = [m for m in servicios.metricas_listado(db) if m["ratio_asimetria"]]
        if not ms:
            raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Métricas aún no disponibles")
        m = max(ms, key=lambda x: x["ratio_asimetria"])
        a = servicios.analisis(db, _empresa(db, m["ticker"]), 40)
        f, c = servicios.cierres(db, _empresa(db, m["ticker"]).id, 21)
        datos = {"ticker": m["ticker"], "nombre": m["nombre"], "beta": m["beta"], "ratio_asimetria": m["ratio_asimetria"],
                 "nivel_volatilidad": m["nivel_volatilidad"], "retorno_30d": (c[-1] / c[0] - 1) * 100,
                 "base100_activo": a["base100_activo"], "base100_indice": a["base100_indice"]}
        cache.set_json("destacado", datos, settings.cache_ttl)
    return datos


@router.get("/demo/fallos", response_model=FallosDemo, include_in_schema=settings.herramientas_prueba, dependencies=protegido)
def ver_fallos():
    if not settings.herramientas_prueba:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No encontrado")
    return FallosDemo(proveedor_caido=bool(cache.get_json("sim:proveedor_caido")),
                      correo_caido=bool(cache.get_json("sim:correo_caido")))


@router.put("/demo/fallos", response_model=FallosDemo, include_in_schema=settings.herramientas_prueba, dependencies=protegido)
def fijar_fallos(f: FallosDemo):
    """Inyección de fallos para la prueba de RNF-06 (solo con HERRAMIENTAS_PRUEBA=1)."""
    if not settings.herramientas_prueba:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No encontrado")
    cache.set_json("sim:proveedor_caido", f.proveedor_caido)
    cache.set_json("sim:correo_caido", f.correo_caido)
    return f
