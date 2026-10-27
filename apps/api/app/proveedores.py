"""Proveedores de datos bursátiles (RF-01) + resiliencia: reintentos con backoff y circuit breaker (RNF-06)."""
import json
import logging
import time
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone

import httpx
import numpy as np

from . import cache
from .auditoria import registrar_llamada
from .config import settings

log = logging.getLogger("asimetria.proveedor")


@dataclass
class Barra:
    fecha: date
    apertura: float
    maximo: float
    minimo: float
    cierre: float
    volumen: int


class ErrorProveedor(Exception):
    pass


class LimiteProveedor(ErrorProveedor):
    """El proveedor rechazó la petición por límite de cuota (HU-01 CA-3)."""


class CircuitoAbierto(ErrorProveedor):
    pass


def dias_habiles(n: int, hasta: date) -> list[date]:
    """Los últimos n días hábiles (lun–vie) terminando en `hasta` inclusive."""
    out, d = [], hasta
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d -= timedelta(days=1)
    return out[::-1]


# ---------------------------------------------------------------- simulado
# (ticker, nombre, sector, último precio, β alcista, β bajista, ruido, dividendos %, sesiones de historia)
CATALOGO_SIMULADO = [
    ("^NDX", "NASDAQ 100", "Índice", 18942.6, 1, 1, 0, None, None),
    ("RTC", "RiTech S.A.S", "Tecnología", 212.40, 2.0, 1.25, 0.012, 0.4, None),
    ("NVC", "NovaChip Corp", "Semiconductores", 88.15, 0.9, 1.05, 0.010, 0.8, None),
    ("QFN", "Quantum Finance", "Fintech", 54.02, 1.15, 0.85, 0.006, 1.9, None),
    ("GLX", "Global Exchange", "Servicios financieros", 140.77, 0.7, 0.8, 0.005, 3.1, None),
    ("ADE", "Andes Energy", "Energía", 36.50, 0.6, 0.55, 0.006, 4.2, None),
    ("SLH", "Solaris Health", "Salud", 97.30, 0.8, 0.6, 0.007, 1.2, None),
    ("PRT", "Pacific Retail", "Consumo", 45.10, 1.1, 1.3, 0.010, 2.0, None),
    ("KLG", "Kappa Logistics", "Industria", 63.80, 1.0, 0.95, 0.008, 1.5, None),
    ("LMB", "Lumen Bio", "Biotecnología", 24.60, 1.6, 1.4, 0.020, 0.0, None),
    ("ORT", "Orion Telecom", "Telecomunicaciones", 18.90, 0.5, 0.7, 0.005, 5.1, None),
    ("HLX", "Helios Renovables", "Energía renovable", 12.40, 1.3, 1.1, 0.015, 0.0, 12),  # HU-03 CA-4
]
SEMILLA = 3  # elegida para reproducir el escenario alcista de 90 días de los mockups
DIAS_HISTORIA = 1260  # ≈ 5 años (HU-02 CA-1)


class ProveedorSimulado:
    nombre = "SIMULADO"
    """Proveedor determinista para desarrollo, pruebas y demostración (no requiere API key).
    Reproduce el caso del documento: índice +2 % → RiTech +4 %; índice −2 % → RiTech −2.5 %."""

    def __init__(self, hasta: date | None = None, prob_fallo: float = 0.03, semilla: int = SEMILLA) -> None:
        self.hasta = hasta or (datetime.now(timezone.utc).date() - timedelta(days=1))
        self.prob_fallo = prob_fallo
        self.rng = np.random.default_rng(semilla)
        self._series = self._generar(semilla)

    def catalogo(self) -> list[tuple[str, str, str]]:
        return [(t, n, s) for t, n, s, *_ in CATALOGO_SIMULADO]

    def _generar(self, semilla: int) -> dict[str, list[Barra]]:
        rng = np.random.default_rng(semilla)
        n = DIAS_HISTORIA
        fechas = dias_habiles(n + 1, self.hasta)
        rm = 0.0005 + 0.012 * rng.standard_normal(n)
        ref_up, ref_down = n - 8, n - 4
        rm[ref_up], rm[ref_down] = 0.02, -0.02
        series = {}
        for t, _, _, ultimo, b_up, b_down, ruido, _, sesiones in CATALOGO_SIMULADO:
            if t == "^NDX":
                ra = rm.copy()
            else:
                ra = 0.0001 + np.where(rm > 0, b_up, b_down) * rm + ruido * rng.standard_normal(n)
                if t == "RTC":
                    ra[ref_up], ra[ref_down] = 0.04, -0.025
            if sesiones:
                ra = ra[-sesiones:]
            precios = [ultimo]
            for r in ra[::-1]:
                precios.insert(0, precios[0] / (1 + r))
            fs = fechas[-len(precios):]
            series[t] = [Barra(f, p, p * 1.004, p * 0.996, p, int(4e5 + rng.random() * 2.4e6)) for f, p in zip(fs, precios)]
        return series

    def historico(self, ticker: str, dias: int = DIAS_HISTORIA) -> list[Barra]:
        return self._series[ticker][-(dias + 1):]

    def dividendos(self, ticker: str) -> float | None:
        return next(c[7] for c in CATALOGO_SIMULADO if c[0] == ticker)

    def cotizaciones(self, ultimos: dict[str, float]) -> dict[str, float]:
        if cache.get_json("sim:proveedor_caido") or self.rng.random() < self.prob_fallo:
            raise ErrorProveedor("Proveedor no disponible")
        return {t: round(p * (1 + self.rng.normal(0, 0.001)), 4) for t, p in ultimos.items()}


# ---------------------------------------------------------------- Alpha Vantage
CATALOGO_REAL = [
    ("^NDX", "NASDAQ 100 (vía QQQ)", "Índice"), ("AAPL", "Apple Inc.", "Tecnología"),
    ("MSFT", "Microsoft Corp.", "Tecnología"), ("NVDA", "NVIDIA Corp.", "Semiconductores"),
    ("AMZN", "Amazon.com Inc.", "Consumo"), ("GOOGL", "Alphabet Inc.", "Tecnología"),
]


class ProveedorAlphaVantage:
    """Cliente HTTP de Alpha Vantage. La API key se lee del entorno y nunca se registra en logs (HU-01 CA-1).
    El índice NASDAQ 100 se aproxima con el ETF QQQ (el plan gratuito no expone ^NDX)."""
    nombre = "ALPHAVANTAGE"
    URL = "https://www.alphavantage.co/query"
    SIMBOLO = {"^NDX": "QQQ"}

    def __init__(self, api_key: str, http: httpx.Client | None = None) -> None:
        if not api_key:
            raise ValueError("ALPHAVANTAGE_API_KEY no está configurada")
        self.key = api_key
        self.http = http or httpx.Client(timeout=10)

    def catalogo(self) -> list[tuple[str, str, str]]:
        return CATALOGO_REAL

    def _get(self, **params) -> dict:
        """Cada llamada queda trazada en `llamadas_externas` (sin la API key). Antes de llamar se consulta la cuota
        diaria local (Redis): al llegar a AV_LIMITE_DIARIO se deja de llamar y se usan los datos almacenados."""
        operacion = params.get("function", "?")
        if self.cuota_usada_hoy() >= settings.av_limite_diario:
            registrar_llamada(self.nombre, operacion, False, None, 0, "Cuota diaria local agotada: no se llamó al proveedor")
            raise LimiteProveedor("Cuota diaria de Alpha Vantage agotada")
        cache.contador(_clave_cuota(), 90000)
        t0, status = time.perf_counter(), None
        try:
            r = self.http.get(self.URL, params={**params, "apikey": self.key})
            status = r.status_code
            r.raise_for_status()
            d = r.json()
            if "Note" in d or "Information" in d:
                raise LimiteProveedor("Límite de peticiones del proveedor alcanzado")
            if "Error Message" in d:
                raise ErrorProveedor(d["Error Message"][:120])
        except Exception as e:
            # Los mensajes de httpx incluyen la URL (con la clave): se enmascara antes de guardar.
            registrar_llamada(self.nombre, operacion, False, status, _ms(t0), str(e).replace(self.key, "***"))
            raise
        registrar_llamada(self.nombre, operacion, True, status, _ms(t0), params.get("symbol", ""))
        return d

    @staticmethod
    def cuota_usada_hoy() -> int:
        return int(cache.get_json(_clave_cuota()) or 0)

    def historico(self, ticker: str, dias: int = DIAS_HISTORIA) -> list[Barra]:
        d = self._get(function="TIME_SERIES_DAILY", symbol=self.SIMBOLO.get(ticker, ticker), outputsize="full")
        filas = sorted(d["Time Series (Daily)"].items())[-(dias + 1):]
        return [Barra(date.fromisoformat(f), float(v["1. open"]), float(v["2. high"]), float(v["3. low"]),
                      float(v["4. close"]), int(v["5. volume"])) for f, v in filas]

    def dividendos(self, ticker: str) -> float | None:
        if ticker in self.SIMBOLO:
            return None
        y = self._get(function="OVERVIEW", symbol=ticker).get("DividendYield")
        return float(y) * 100 if y not in (None, "None", "") else None

    def cotizaciones(self, ultimos: dict[str, float]) -> dict[str, float]:
        out = {}
        for t in ultimos:
            q = self._get(function="GLOBAL_QUOTE", symbol=self.SIMBOLO.get(t, t))["Global Quote"]
            out[t] = float(q["05. price"])
        return out


def _ms(t0: float) -> int:
    return int((time.perf_counter() - t0) * 1000)


def _clave_cuota() -> str:
    return f"cuota:alphavantage:{datetime.now(timezone.utc).date()}"


# ---------------------------------------------------------------- resiliencia
def con_resiliencia(fn, *args, intentos: int = 3, base: float = 0.15, dormir=time.sleep):
    """Reintentos con backoff exponencial y circuit breaker compartido vía caché (Redis) entre procesos.
    Tras 3 ciclos fallidos el circuito se abre 30 s y no se molesta al proveedor."""
    abierto_hasta = cache.get_json("cb:abierto_hasta")
    if abierto_hasta and abierto_hasta > time.time():
        raise CircuitoAbierto("Circuito abierto: se usan los últimos datos almacenados")
    for i in range(intentos):
        try:
            r = fn(*args)
            cache.set_json("cb:fallos", 0)
            return r
        except Exception as e:  # noqa: BLE001 — cualquier fallo externo se trata igual
            evento = "LIMITE_PROVEEDOR" if isinstance(e, LimiteProveedor) else "PROVEEDOR_ERROR"
            log.warning(json.dumps({"ts": datetime.now(timezone.utc).isoformat(), "evento": evento,
                                    "intento": i + 1, "detalle": str(e)[:120]}))
            if i < intentos - 1:
                dormir(base * 2 ** i)
    fallos = (cache.get_json("cb:fallos") or 0) + 1
    cache.set_json("cb:fallos", fallos)
    if fallos >= 3:
        cache.set_json("cb:abierto_hasta", time.time() + 30, ttl=30)
        cache.set_json("cb:fallos", 0)
    raise ErrorProveedor("Proveedor no disponible tras 3 intentos")


_proveedor = None


def obtener_proveedor():
    global _proveedor
    if _proveedor is None:
        _proveedor = (ProveedorAlphaVantage(settings.alphavantage_key) if settings.proveedor == "alphavantage"
                      else ProveedorSimulado())
    return _proveedor
