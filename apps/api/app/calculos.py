"""Motor de cálculo (RF-03, RF-04, RF-05, RF-06, HU-07). Funciones puras con NumPy, sin E/S."""
from collections.abc import Sequence

import numpy as np

ERR_DATOS = "Datos insuficientes para calcular la beta"
MIN_OBS = 20  # observaciones mínimas por régimen (alcista y bajista)
MARGEN_PERFIL = {"CONSERVADOR": 20.0, "MODERADO": 35.0, "AGRESIVO": float("inf")}  # volatilidad anual máx. (%)
TASA_LIBRE_RIESGO = 4.0  # % anual, para el CAPM


class DatosInsuficientes(ValueError):
    pass


def retornos(precios: Sequence[float]) -> np.ndarray:
    p = np.asarray(precios, dtype=float)
    return p[1:] / p[:-1] - 1


def beta(ra: np.ndarray, rm: np.ndarray) -> float | None:
    """Regresión al origen Σ(ra·rm)/Σ(rm²). Con un dato equivale a ra/rm (caso RiTech)."""
    den = float(np.dot(rm, rm))
    return None if den == 0 else float(np.dot(ra, rm)) / den


def beta_asimetrica(ra: Sequence[float], rm: Sequence[float], minimo: int = MIN_OBS) -> dict:
    """HU-03: beta alcista (índice sube) y bajista (índice baja)."""
    ra, rm = np.asarray(ra, dtype=float), np.asarray(rm, dtype=float)
    if ra.shape != rm.shape:
        raise DatosInsuficientes(ERR_DATOS)
    up, down = rm > 0, rm < 0
    if up.sum() < minimo or down.sum() < minimo:
        raise DatosInsuficientes(ERR_DATOS)
    alcista, bajista = beta(ra[up], rm[up]), beta(ra[down], rm[down])
    return {"alcista": alcista, "bajista": bajista, "global": beta(ra, rm),
            "ratio": alcista / bajista if bajista else None, "favorable": alcista > bajista}


def volatilidad(ra: Sequence[float]) -> float:
    """Desviación estándar muestral anualizada (√252), en %."""
    ra = np.asarray(ra, dtype=float)
    return 0.0 if ra.size < 2 else float(np.std(ra, ddof=1) * np.sqrt(252) * 100)


def correlacion(a: Sequence[float], b: Sequence[float]) -> float | None:
    a, b = np.asarray(a, dtype=float), np.asarray(b, dtype=float)
    if a.size < 2 or np.std(a) == 0 or np.std(b) == 0:
        return None
    return float(np.corrcoef(a, b)[0, 1])


def max_drawdown(precios: Sequence[float]) -> float:
    p = np.asarray(precios, dtype=float)
    return float(np.min(p / np.maximum.accumulate(p) - 1) * 100)


def nivel_volatilidad(vol: float) -> str:
    return "Baja" if vol < 20 else "Media" if vol < 35 else "Alta"


def retorno_esperado_capm(beta_global: float | None, rm: Sequence[float]) -> float | None:
    """Proyección anual (%) por CAPM: rf + β·(E[Rm] − rf), con E[Rm] histórico anualizado del índice."""
    if beta_global is None or len(rm) == 0:
        return None
    mercado = float(np.mean(rm)) * 252 * 100
    return TASA_LIBRE_RIESGO + beta_global * (mercado - TASA_LIBRE_RIESGO)


def simular(monto: float, precios_activo: Sequence[float], precios_indice: Sequence[float],
            fechas: Sequence | None = None) -> dict:
    """HU-05: backtesting comprar-y-mantener. Serie tabulada (fecha, portafolio, índice) para graficar."""
    if not monto or monto <= 0:
        raise ValueError("El monto inicial debe ser mayor que 0")
    if len(precios_activo) < 2 or len(precios_activo) != len(precios_indice):
        raise ValueError("No hay datos históricos para el periodo seleccionado")
    a0, m0 = precios_activo[0], precios_indice[0]
    serie = [{"fecha": fechas[i] if fechas is not None else i,
              "portafolio": round(monto * p / a0, 2),
              "indice": round(monto * precios_indice[i] / m0, 2)} for i, p in enumerate(precios_activo)]
    fin = serie[-1]
    return {"serie": serie, "valor_final": fin["portafolio"], "valor_indice": fin["indice"],
            "retorno": (fin["portafolio"] / monto - 1) * 100, "retorno_indice": (fin["indice"] / monto - 1) * 100}


def recomendar(activos: list[dict], perfil: str | None, vol_max: float | None = None,
               retorno_min: float | None = None, ratio_min: float | None = None) -> list[dict]:
    """HU-04: filtra por perfil y filtros; prioriza por retorno ajustado a riesgo × asimetría (β↑/β↓)."""
    if perfil not in MARGEN_PERFIL:
        raise PermissionError("Completa el quiz de perfil de riesgo para ver recomendaciones")
    tope = min(MARGEN_PERFIL[perfil], vol_max if vol_max is not None else float("inf"))
    salida = []
    for a in activos:
        if a.get("beta_alcista") is None or not a.get("volatilidad") or a.get("retorno_esperado") is None:
            continue  # sin beta confiable no se recomienda (HU-03 CA-4)
        ratio_rb = a["retorno_esperado"] / a["volatilidad"]
        if a["volatilidad"] > tope or (retorno_min is not None and a["retorno_esperado"] < retorno_min) \
                or (ratio_min is not None and ratio_rb < ratio_min):
            continue
        salida.append({**a, "ratio_riesgo_beneficio": ratio_rb,
                       "puntaje": ratio_rb * (a["beta_alcista"] / a["beta_bajista"])})
    return sorted(salida, key=lambda x: x["puntaje"], reverse=True)


def perfil_desde_quiz(puntos: Sequence[int]) -> str:
    """HU-07 CA-3: promedio de respuestas (1–3) → perfil."""
    if not puntos or any(p not in (1, 2, 3) for p in puntos):
        raise ValueError("Responde todas las preguntas del quiz")
    prom = sum(puntos) / len(puntos)
    return "CONSERVADOR" if prom < 1.67 else "MODERADO" if prom < 2.34 else "AGRESIVO"


def evaluar_alerta(tipo: str, umbral: float, precio: float, variacion: float) -> bool:
    """HU-06: VARIACION_PORCENTUAL → |var| > umbral; PRECIO_LIMITE → precio ≥ umbral."""
    return precio >= umbral if tipo == "PRECIO_LIMITE" else abs(variacion) > umbral
