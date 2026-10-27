"""Trazabilidad de integraciones externas (Alpha Vantage, SendGrid) y procedencia de los datos almacenados."""
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..config import settings
from ..db import get_db
from ..models import LlamadaExterna, PrecioHistorico, Usuario
from ..proveedores import ErrorProveedor, ProveedorAlphaVantage
from ..security import usuario_actual
from ..servicios import ErrorNotificacion, enviar_sendgrid

router = APIRouter(tags=["Integraciones y trazabilidad"])


def _resumen(db: Session, proveedor: str, desde: datetime) -> dict:
    filas = db.scalars(select(LlamadaExterna).where(LlamadaExterna.proveedor == proveedor, LlamadaExterna.ts >= desde)
                       .order_by(LlamadaExterna.ts)).all()
    lat = sorted(f.latencia_ms for f in filas if f.http_status is not None)
    ultima = filas[-1] if filas else None
    return {"llamadas_24h": len(filas), "exitosas_24h": sum(f.exito for f in filas),
            "latencia_p95_ms": lat[max(0, int(len(lat) * 0.95) - 1)] if lat else None,
            "ultima": None if ultima is None else {"ts": ultima.ts, "operacion": ultima.operacion, "exito": ultima.exito,
                                                    "http_status": ultima.http_status, "detalle": ultima.detalle}}


@router.get("/integraciones")
def integraciones(_: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    """Estado de cada integración (sin exponer claves), cuota usada, llamadas recientes y origen de los precios."""
    desde = datetime.now(timezone.utc) - timedelta(hours=24)
    recientes = db.scalars(select(LlamadaExterna).order_by(LlamadaExterna.id.desc()).limit(30)).all()
    origen = db.execute(select(PrecioHistorico.fuente, func.count(), func.max(PrecioHistorico.fecha_ingesta))
                        .group_by(PrecioHistorico.fuente)).all()
    return {
        "proveedores": [
            {"proveedor": "ALPHAVANTAGE", "configurado": bool(settings.alphavantage_key),
             "en_uso_para_precios": settings.proveedor == "alphavantage",
             "cuota_usada_hoy": ProveedorAlphaVantage.cuota_usada_hoy(), "cuota_limite_diaria": settings.av_limite_diario,
             **_resumen(db, "ALPHAVANTAGE", desde)},
            {"proveedor": "SENDGRID", "configurado": bool(settings.sendgrid_key),
             "remitente": settings.correo_remitente.split("@")[-1] if settings.sendgrid_key else None,
             **_resumen(db, "SENDGRID", desde)},
        ],
        "recientes": [{"ts": r.ts, "proveedor": r.proveedor, "operacion": r.operacion, "exito": r.exito,
                       "http_status": r.http_status, "latencia_ms": r.latencia_ms, "detalle": r.detalle} for r in recientes],
        "origen_precios": [{"fuente": f or "SIN_REGISTRO", "filas": n, "ultima_ingesta": u} for f, n, u in origen],
    }


@router.post("/integraciones/probar", include_in_schema=settings.herramientas_prueba)
def probar(_: Usuario = Depends(usuario_actual)):
    """Verifica las claves contra los servicios reales (solo MODO_DEMO). Alpha Vantage: 1 consulta (gasta 1 de cuota).
    SendGrid: envío en *sandbox mode* — valida clave y remitente sin entregar ningún correo."""
    if not settings.herramientas_prueba:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No encontrado")
    res = {}
    if settings.alphavantage_key:
        try:
            q = ProveedorAlphaVantage(settings.alphavantage_key)._get(function="GLOBAL_QUOTE", symbol="QQQ")["Global Quote"]
            res["ALPHAVANTAGE"] = {"ok": True, "detalle": f"QQQ (proxy NASDAQ 100) = {q.get('05. price')} USD"}
        except ErrorProveedor as e:
            res["ALPHAVANTAGE"] = {"ok": False, "detalle": str(e)}
        except Exception:  # noqa: BLE001 — red, JSON inesperado: ya quedó trazado
            res["ALPHAVANTAGE"] = {"ok": False, "detalle": "No fue posible consultar Alpha Vantage"}
    else:
        res["ALPHAVANTAGE"] = {"ok": False, "detalle": "No configurado (ALPHAVANTAGE_API_KEY vacío)"}
    if settings.sendgrid_key:
        try:
            enviar_sendgrid(settings.correo_remitente, "Prueba de integración Asimetría", "Prueba sandbox", sandbox=True)
            res["SENDGRID"] = {"ok": True, "detalle": "Clave y remitente aceptados (sandbox: no se envió correo)"}
        except ErrorNotificacion as e:
            res["SENDGRID"] = {"ok": False, "detalle": str(e)}
    else:
        res["SENDGRID"] = {"ok": False, "detalle": "No configurado (SENDGRID_API_KEY vacío)"}
    return res
