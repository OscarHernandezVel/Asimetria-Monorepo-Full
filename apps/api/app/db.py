"""Motor SQLAlchemy. PostgreSQL + TimescaleDB en producción; SQLite para pruebas/desarrollo."""
from collections.abc import AsyncIterator
from contextlib import contextmanager

from sqlalchemy import create_engine, text
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker
from sqlalchemy.pool import StaticPool

from .config import settings

_url = settings.database_url
_es_sqlite = _url.startswith("sqlite")
_pool = {} if _es_sqlite else {"pool_size": 10, "max_overflow": 10, "pool_timeout": 5}
# 4 workers × 20 conexiones + Celery < max_connections (100). pool_timeout corto: falla rápido (503), no cuelga.
engine = create_engine(
    _url,
    pool_pre_ping=True,
    connect_args={"check_same_thread": False} if _es_sqlite else {},
    poolclass=StaticPool if _url == "sqlite://" else None,  # BD en memoria compartida (pruebas)
    **_pool,
)
SessionLocal = sessionmaker(engine, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


async def get_db() -> AsyncIterator[Session]:
    """Async a propósito: el cierre de la sesión (devolver la conexión al pool) corre en el event loop y no
    compite por hilos con los endpoints síncronos. Con un generador síncrono, bajo carga todos los hilos
    quedan esperando conexiones que solo se liberan cuando un hilo libre ejecuta la limpieza: interbloqueo
    hasta el pool_timeout (detectado en la prueba de carga de RNF-01)."""
    s = SessionLocal()
    try:
        yield s
    finally:
        s.close()


def init_db() -> None:
    """Crea las tablas que falten. En PostgreSQL además asegura la hypertable (RNF-02)."""
    from . import models  # noqa: F401  registra los modelos

    Base.metadata.create_all(engine)
    if engine.dialect.name == "postgresql":
        with engine.begin() as c:
            c.execute(text("CREATE EXTENSION IF NOT EXISTS timescaledb"))
            c.execute(text("SELECT create_hypertable('precios_historicos', 'time', if_not_exists => TRUE, migrate_data => TRUE)"))
            _migrar_postgres(c)


# Columnas añadidas después de la primera versión: create_all no altera tablas existentes.
_COLUMNAS_NUEVAS = [
    ("precios_historicos", "fuente", "VARCHAR(20)"),
    ("precios_historicos", "fecha_ingesta", "TIMESTAMPTZ"),
    ("notificaciones", "estado_envio", "VARCHAR(20)"),
    ("notificaciones", "proveedor_id", "VARCHAR(100)"),
]


def _migrar_postgres(c) -> None:
    """Migración idempotente para volúmenes creados con la versión anterior + compresión de TimescaleDB."""
    for tabla, columna, tipo in _COLUMNAS_NUEVAS:
        existe = c.execute(text("SELECT 1 FROM information_schema.columns WHERE table_name = :t AND column_name = :c"),
                           {"t": tabla, "c": columna}).first()
        if not existe:
            c.execute(text(f"ALTER TABLE {tabla} ADD COLUMN {columna} {tipo}"))
    comprimida = c.execute(text("SELECT compression_enabled FROM timescaledb_information.hypertables "
                                "WHERE hypertable_name = 'precios_historicos'")).scalar()
    if not comprimida:  # RNF-02: chunks > 90 días en formato columnar (≈ 90 % menos disco, barridos más rápidos)
        c.execute(text("ALTER TABLE precios_historicos SET (timescaledb.compress, "
                       "timescaledb.compress_segmentby = 'empresa_id', timescaledb.compress_orderby = 'time DESC')"))
    c.execute(text("SELECT add_compression_policy('precios_historicos', INTERVAL '90 days', if_not_exists => TRUE)"))


@contextmanager
def bloqueo_arranque():
    """Serializa el arranque entre procesos (uvicorn --workers N): solo uno crea el esquema y hace la carga
    inicial a la vez; los demás esperan y luego la encuentran hecha. En SQLite no hace falta."""
    if engine.dialect.name != "postgresql":
        yield
        return
    with engine.connect() as c:
        c.execute(text("SELECT pg_advisory_lock(424242)"))
        try:
            yield
        finally:
            c.execute(text("SELECT pg_advisory_unlock(424242)"))
