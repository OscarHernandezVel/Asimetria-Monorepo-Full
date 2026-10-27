"""Registro de auditoría (RNF-04): JSON estructurado a stdout (para el centralizador de logs) + tabla `auditoria`."""
import json
import logging
import sys
from datetime import datetime, timedelta, timezone

from sqlalchemy import delete
from sqlalchemy.orm import Session

from .models import Auditoria, LlamadaExterna, Usuario

log = logging.getLogger("asimetria.auditoria")
if not log.handlers:
    _h = logging.StreamHandler(sys.stdout)
    _h.setFormatter(logging.Formatter("%(message)s"))
    log.addHandler(_h)
    log.setLevel(logging.INFO)
    log.propagate = False

RETENCION_DIAS = 90


def registrar(db: Session, evento: str, estado: str = "EXITO", usuario: Usuario | None = None,
              ip: str | None = None, detalle: str = "", email: str | None = None) -> None:
    reg = Auditoria(ts=datetime.now(timezone.utc), usuario_id=usuario.id if usuario else None,
                    email=(usuario.email if usuario else email), ip=ip, evento=evento, estado=estado,
                    detalle=detalle[:255])
    log.info(json.dumps({"ts": reg.ts.isoformat(), "usuario_id": reg.usuario_id, "email": reg.email, "ip": ip,
                         "evento": evento, "estado": estado, "detalle": reg.detalle}, ensure_ascii=False))
    db.add(reg)
    db.commit()


def registrar_llamada(proveedor: str, operacion: str, exito: bool, http_status: int | None, latencia_ms: int,
                      detalle: str = "") -> None:
    """Traza una llamada externa en su propia sesión. Nunca lanza: la trazabilidad no debe romper el flujo."""
    from .db import SessionLocal

    reg = {"ts": datetime.now(timezone.utc).isoformat(), "evento": "LLAMADA_EXTERNA", "proveedor": proveedor,
           "operacion": operacion, "exito": exito, "http_status": http_status, "latencia_ms": latencia_ms,
           "detalle": (detalle or "")[:255]}
    log.info(json.dumps(reg, ensure_ascii=False))
    try:
        with SessionLocal() as db:
            db.add(LlamadaExterna(proveedor=proveedor, operacion=operacion[:40], exito=exito, http_status=http_status,
                                  latencia_ms=latencia_ms, detalle=reg["detalle"]))
            db.commit()
    except Exception:  # noqa: BLE001
        logging.getLogger("asimetria").exception("No se pudo guardar la traza de la llamada externa")


def purgar(db: Session) -> int:
    """Elimina auditoría y trazas de llamadas externas con más de 90 días (tarea diaria de Celery)."""
    limite = datetime.now(timezone.utc) - timedelta(days=RETENCION_DIAS)
    n = db.execute(delete(Auditoria).where(Auditoria.ts < limite)).rowcount
    n += db.execute(delete(LlamadaExterna).where(LlamadaExterna.ts < limite)).rowcount
    db.commit()
    return n
