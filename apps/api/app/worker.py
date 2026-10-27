"""Worker Celery + beat (RF-02, RF-06, RNF-04). Ejecutar:
    celery -A app.worker worker -l info
    celery -A app.worker beat -l info
"""
from celery import Celery
from celery.schedules import crontab

from . import servicios
from .auditoria import purgar
from .config import settings
from .db import SessionLocal  # el esquema lo crea la API al arrancar (el worker espera a que esté sana)

celery = Celery("asimetria", broker=settings.redis_url or "memory://", backend=None)
celery.conf.update(timezone="UTC", task_acks_late=True, worker_prefetch_multiplier=1, task_ignore_result=True)
celery.conf.beat_schedule = {
    "cotizaciones": {"task": "app.worker.sincronizar_cotizaciones", "schedule": float(settings.intervalo_cotizaciones)},
    "alertas": {"task": "app.worker.evaluar_alertas", "schedule": 60.0},
    "cierre-diario": {"task": "app.worker.cierre_diario", "schedule": crontab(hour=21, minute=30, day_of_week="mon-fri")},
    "purga-auditoria": {"task": "app.worker.purgar_auditoria", "schedule": crontab(hour=3, minute=0)},
}


def _con_db(fn):
    with SessionLocal() as db:
        return fn(db)


@celery.task(name="app.worker.sincronizar_cotizaciones", expires=10)
def sincronizar_cotizaciones():
    return _con_db(servicios.sincronizar_cotizaciones)


@celery.task(name="app.worker.evaluar_alertas", expires=50)
def evaluar_alertas():
    return _con_db(servicios.evaluar_alertas)


@celery.task(name="app.worker.cierre_diario", autoretry_for=(Exception,), retry_backoff=True, max_retries=5)
def cierre_diario():
    return _con_db(servicios.cierre_diario)


@celery.task(name="app.worker.purgar_auditoria")
def purgar_auditoria():
    return _con_db(purgar)
