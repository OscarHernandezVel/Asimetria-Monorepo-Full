"""Modelos ORM. Reflejan packages/db/schema.sql (una prueba verifica que no diverjan)."""
from datetime import date, datetime, timezone
from decimal import Decimal

from sqlalchemy import (BigInteger, Boolean, CheckConstraint, Date, DateTime, ForeignKey, Index, Integer,
                        Numeric, String, UniqueConstraint)
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base

ahora = lambda: datetime.now(timezone.utc)  # noqa: E731
# BIGINT en PostgreSQL; INTEGER en SQLite (para que AUTOINCREMENT funcione).
BigId = BigInteger().with_variant(Integer, "sqlite")


class Usuario(Base):
    __tablename__ = "usuarios"
    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    email: Mapped[str] = mapped_column(String(255), unique=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    nombre: Mapped[str] = mapped_column(String(100))
    perfil_riesgo: Mapped[str | None] = mapped_column(String(20))
    plan: Mapped[str | None] = mapped_column(String(20))
    fecha_registro: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=ahora)
    __table_args__ = (CheckConstraint("perfil_riesgo IN ('CONSERVADOR','MODERADO','AGRESIVO')", name="ck_perfil"),)


class Empresa(Base):
    __tablename__ = "empresas"
    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    ticker: Mapped[str] = mapped_column(String(10), unique=True)
    nombre_empresa: Mapped[str] = mapped_column(String(100))
    sector: Mapped[str | None] = mapped_column(String(50))
    es_indice: Mapped[bool] = mapped_column(Boolean, default=False)
    activa: Mapped[bool] = mapped_column(Boolean, default=True)


class PrecioHistorico(Base):
    __tablename__ = "precios_historicos"
    time: Mapped[datetime] = mapped_column(DateTime(timezone=True), primary_key=True)
    empresa_id: Mapped[int] = mapped_column(ForeignKey("empresas.id"), primary_key=True)
    precio_apertura: Mapped[Decimal | None] = mapped_column(Numeric(12, 4))
    precio_maximo: Mapped[Decimal | None] = mapped_column(Numeric(12, 4))
    precio_minimo: Mapped[Decimal | None] = mapped_column(Numeric(12, 4))
    precio_cierre: Mapped[Decimal] = mapped_column(Numeric(12, 4))
    volumen: Mapped[int | None] = mapped_column(BigInteger)
    fuente: Mapped[str | None] = mapped_column(String(20))  # procedencia: SIMULADO | ALPHAVANTAGE | COTIZACION_VIVO
    fecha_ingesta: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), default=ahora)
    __table_args__ = (Index("idx_precios_empresa_time", "empresa_id", "time"),)


class MetricaEmpresa(Base):
    __tablename__ = "metricas_empresa"
    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    empresa_id: Mapped[int] = mapped_column(ForeignKey("empresas.id"), unique=True)
    beta_alcista: Mapped[Decimal | None] = mapped_column(Numeric(8, 4))
    beta_bajista: Mapped[Decimal | None] = mapped_column(Numeric(8, 4))
    beta: Mapped[Decimal | None] = mapped_column(Numeric(8, 4))
    correlacion: Mapped[Decimal | None] = mapped_column(Numeric(8, 4))
    volatilidad: Mapped[Decimal] = mapped_column(Numeric(8, 4))
    retorno_esperado: Mapped[Decimal | None] = mapped_column(Numeric(8, 4))
    rendimiento_dividendos: Mapped[Decimal | None] = mapped_column(Numeric(8, 4))
    fecha_actualizacion: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=ahora, onupdate=ahora)
    __table_args__ = (Index("idx_metricas_filtro", "volatilidad", "beta_alcista", "beta_bajista"),)


class ListaSeguimiento(Base):
    __tablename__ = "listas_seguimiento"
    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    usuario_id: Mapped[int] = mapped_column(ForeignKey("usuarios.id", ondelete="CASCADE"))
    empresa_id: Mapped[int] = mapped_column(ForeignKey("empresas.id"))
    cantidad: Mapped[int] = mapped_column(Integer, default=0)
    favorito: Mapped[bool] = mapped_column(Boolean, default=False)
    fecha_agregado: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=ahora)
    __table_args__ = (UniqueConstraint("usuario_id", "empresa_id", name="uq_lista_usuario_empresa"),  # HU-08 CA-4
                      CheckConstraint("cantidad >= 0", name="ck_cantidad"))


class ReglaAlerta(Base):
    __tablename__ = "reglas_alertas"
    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    usuario_id: Mapped[int] = mapped_column(ForeignKey("usuarios.id", ondelete="CASCADE"))
    empresa_id: Mapped[int] = mapped_column(ForeignKey("empresas.id"))
    tipo_condicion: Mapped[str] = mapped_column(String(30))
    umbral: Mapped[Decimal] = mapped_column(Numeric(12, 4))
    canal: Mapped[str] = mapped_column(String(10), default="CORREO")
    estado: Mapped[str] = mapped_column(String(20), default="ACTIVA")
    pendiente_envio: Mapped[bool] = mapped_column(Boolean, default=False)
    fecha_creacion: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=ahora)
    __table_args__ = (
        CheckConstraint("tipo_condicion IN ('VARIACION_PORCENTUAL','PRECIO_LIMITE')", name="ck_tipo"),
        CheckConstraint("estado IN ('ACTIVA','DISPARADA','INACTIVA')", name="ck_estado"),
        CheckConstraint("canal IN ('CORREO','PUSH')", name="ck_canal"),
        CheckConstraint("umbral > 0", name="ck_umbral"),
    )


class Notificacion(Base):
    __tablename__ = "notificaciones"
    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    usuario_id: Mapped[int] = mapped_column(ForeignKey("usuarios.id", ondelete="CASCADE"), index=True)
    regla_id: Mapped[int | None] = mapped_column(ForeignKey("reglas_alertas.id", ondelete="SET NULL"))
    texto: Mapped[str] = mapped_column(String(255))
    canal: Mapped[str] = mapped_column(String(10))
    estado_envio: Mapped[str | None] = mapped_column(String(20))  # ENVIADO_CORREO | EN_APP
    proveedor_id: Mapped[str | None] = mapped_column(String(100))  # X-Message-Id de SendGrid (rastreo de entrega)
    fecha: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=ahora)


class SimulacionPortafolio(Base):
    __tablename__ = "simulaciones_portafolio"
    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    usuario_id: Mapped[int] = mapped_column(ForeignKey("usuarios.id", ondelete="CASCADE"))
    empresa_id: Mapped[int] = mapped_column(ForeignKey("empresas.id"))
    monto_inicial: Mapped[Decimal] = mapped_column(Numeric(12, 2))
    fecha_inicio: Mapped[date] = mapped_column(Date)
    fecha_fin: Mapped[date] = mapped_column(Date)
    valor_final: Mapped[Decimal] = mapped_column(Numeric(12, 2))
    fecha_ejecucion: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=ahora)
    __table_args__ = (CheckConstraint("monto_inicial > 0", name="ck_monto"),  # HU-05 CA-4
                      CheckConstraint("fecha_fin > fecha_inicio", name="ck_fechas"))


class Auditoria(Base):
    __tablename__ = "auditoria"
    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    ts: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=ahora, index=True)
    usuario_id: Mapped[int | None] = mapped_column(ForeignKey("usuarios.id", ondelete="SET NULL"), index=True)
    email: Mapped[str | None] = mapped_column(String(255))
    ip: Mapped[str | None] = mapped_column(String(45))
    evento: Mapped[str] = mapped_column(String(40))
    estado: Mapped[str] = mapped_column(String(10))
    detalle: Mapped[str | None] = mapped_column(String(255))


class LlamadaExterna(Base):
    """Traza de cada llamada a un servicio externo (Alpha Vantage, SendGrid). Nunca guarda secretos."""
    __tablename__ = "llamadas_externas"
    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    ts: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=ahora)
    proveedor: Mapped[str] = mapped_column(String(20))
    operacion: Mapped[str] = mapped_column(String(40))
    exito: Mapped[bool] = mapped_column(Boolean)
    http_status: Mapped[int | None] = mapped_column(Integer)
    latencia_ms: Mapped[int] = mapped_column(Integer)
    detalle: Mapped[str | None] = mapped_column(String(255))
    __table_args__ = (Index("ix_llamadas_proveedor_ts", "proveedor", "ts"),)
