"""Esquemas de petición/respuesta. Quedan documentados en OpenAPI 3 (/docs) — RNF-07."""
from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator

Perfil = Literal["CONSERVADOR", "MODERADO", "AGRESIVO"]


class RegistroIn(BaseModel):
    nombre: str = Field(min_length=1, max_length=100)
    email: EmailStr
    password: str = Field(min_length=8, max_length=72)  # bcrypt usa como máximo 72 bytes
    perfil: Perfil | None = None

    @field_validator("password")
    @classmethod
    def fuerte(cls, v: str) -> str:
        if not any(c.isalpha() for c in v) or not any(c.isdigit() for c in v) or len(v.encode()) > 72:
            raise ValueError("La contraseña debe tener mínimo 8 caracteres, con letras y números")
        return v

    @field_validator("nombre")
    @classmethod
    def limpio(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("Ingresa tu nombre completo")
        return v.strip()


class LoginIn(BaseModel):
    email: EmailStr
    password: str = Field(max_length=200)


class UsuarioOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    email: str
    nombre: str
    perfil_riesgo: Perfil | None
    plan: str | None


class TokenOut(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expira: datetime
    usuario: UsuarioOut


class QuizIn(BaseModel):
    respuestas: list[int] = Field(min_length=5, max_length=5)


class PlanIn(BaseModel):
    plan: Perfil


class MetricaOut(BaseModel):
    ticker: str
    nombre: str
    sector: str | None
    beta_alcista: float | None
    beta_bajista: float | None
    beta: float | None
    ratio_asimetria: float | None
    correlacion: float | None
    volatilidad: float
    nivel_volatilidad: str
    retorno_esperado: float | None
    dividendos: float | None
    error: str | None
    tendencia: list[float]
    fecha_actualizacion: str


class RecomendacionOut(MetricaOut):
    ratio_riesgo_beneficio: float
    puntaje: float


class CotizacionOut(BaseModel):
    precio: float
    cierre_anterior: float
    variacion: float


class EstadoCotizaciones(BaseModel):
    desactualizado: bool
    motivo: str | None
    ultima_actualizacion: str | None


class CotizacionesOut(BaseModel):
    estado: EstadoCotizaciones
    cotizaciones: dict[str, CotizacionOut]


class EmpresaOut(BaseModel):
    ticker: str
    nombre: str
    sector: str | None
    es_indice: bool


class SerieOut(BaseModel):
    ticker: str
    fechas: list[date]
    cierres: list[float]


class WatchIn(BaseModel):
    ticker: str = Field(max_length=10)


class WatchPatch(BaseModel):
    cantidad: int | None = Field(default=None, ge=0, le=10_000_000)
    favorito: bool | None = None


class WatchOut(BaseModel):
    ticker: str
    nombre: str
    sector: str | None
    cantidad: int
    favorito: bool
    fecha_agregado: datetime


class SimulacionIn(BaseModel):
    ticker: str = Field(max_length=10)
    monto: float
    fecha_inicio: date
    fecha_fin: date


class PuntoSimulacion(BaseModel):
    fecha: date
    portafolio: float
    indice: float


class SimulacionOut(BaseModel):
    id: int
    ticker: str
    monto: float
    fecha_inicio: date
    fecha_fin: date
    valor_final: float
    valor_indice: float
    retorno: float
    retorno_indice: float
    serie: list[PuntoSimulacion]


class SimulacionHist(BaseModel):
    id: int
    ticker: str
    monto: float
    fecha_inicio: date
    fecha_fin: date
    valor_final: float
    fecha_ejecucion: datetime


class AlertaIn(BaseModel):
    ticker: str = Field(max_length=10)
    tipo: Literal["VARIACION_PORCENTUAL", "PRECIO_LIMITE"]
    umbral: float = Field(gt=0, lt=100_000_000)
    canal: Literal["CORREO", "PUSH"] = "CORREO"


class AlertaPatch(BaseModel):
    estado: Literal["ACTIVA", "INACTIVA"]


class AlertaOut(BaseModel):
    id: int
    ticker: str
    nombre: str
    tipo: str
    umbral: float
    canal: str
    estado: str
    pendiente_envio: bool
    fecha_creacion: datetime


class NotificacionOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    texto: str
    canal: str
    estado_envio: str | None
    proveedor_id: str | None
    fecha: datetime


class AuditoriaOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    ts: datetime
    email: str | None
    ip: str | None
    evento: str
    estado: str
    detalle: str | None


class FallosDemo(BaseModel):
    proveedor_caido: bool = False
    correo_caido: bool = False
