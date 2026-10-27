"""Criterios de aceptación del documento sobre el motor de cálculo."""
import pytest

from app import calculos as c


def test_hu03_caso_ritech_beta_alcista_2_y_bajista_1_25():
    b = c.beta_asimetrica([0.04, -0.025], [0.02, -0.02], minimo=1)
    assert b["alcista"] == pytest.approx(2.0)
    assert b["bajista"] == pytest.approx(1.25)
    assert b["favorable"] is True


def test_hu03_ca4_datos_insuficientes():
    with pytest.raises(c.DatosInsuficientes, match="Datos insuficientes"):
        c.beta_asimetrica([0.04], [0.02], minimo=1)
    with pytest.raises(c.DatosInsuficientes):
        c.beta_asimetrica([0.01, 0.02], [0.01, -0.01])
    with pytest.raises(c.DatosInsuficientes):
        c.beta_asimetrica([0.01], [0.01, 0.02])


def test_hu05_simulacion_1040_y_975():
    assert c.simular(1000, [100, 104], [100, 102])["valor_final"] == 1040
    assert c.simular(1000, [100, 97.5], [100, 98])["valor_final"] == 975
    assert set(c.simular(1000, [100, 104], [100, 102], ["d1", "d2"])["serie"][1]) == {"fecha", "portafolio", "indice"}


@pytest.mark.parametrize("monto,pa", [(0, [1, 2]), (-5, [1, 2]), (100, [1])])
def test_hu05_ca4_datos_invalidos(monto, pa):
    with pytest.raises(ValueError):
        c.simular(monto, pa, pa)


ACTIVOS = [
    {"ticker": "A", "volatilidad": 15, "retorno_esperado": 7.5, "beta_alcista": 1, "beta_bajista": 1},
    {"ticker": "B", "volatilidad": 30, "retorno_esperado": 12, "beta_alcista": 2, "beta_bajista": 1.25},
    {"ticker": "C", "volatilidad": 50, "retorno_esperado": 20, "beta_alcista": 1.5, "beta_bajista": 1.5},
    {"ticker": "D", "volatilidad": 40, "retorno_esperado": None, "beta_alcista": None, "beta_bajista": None},
]


def test_hu04_recomendaciones():
    assert [a["ticker"] for a in c.recomendar(ACTIVOS, "CONSERVADOR")] == ["A"]  # CA-1
    assert [a["ticker"] for a in c.recomendar(ACTIVOS, "AGRESIVO", retorno_min=10)] == ["B", "C"]  # CA-2/CA-3
    assert [a["ticker"] for a in c.recomendar(ACTIVOS, "AGRESIVO", vol_max=20)] == ["A"]
    assert [a["ticker"] for a in c.recomendar(ACTIVOS, "AGRESIVO", ratio_min=0.41)] == ["A"]
    assert c.recomendar(ACTIVOS, "AGRESIVO", retorno_min=99) == []  # CA-4
    with pytest.raises(PermissionError, match="quiz"):  # CA-5
        c.recomendar(ACTIVOS, None)


def test_hu07_quiz():
    assert c.perfil_desde_quiz([1, 1, 1, 2, 1]) == "CONSERVADOR"
    assert c.perfil_desde_quiz([2, 2, 2, 2, 2]) == "MODERADO"
    assert c.perfil_desde_quiz([3, 3, 3, 2, 3]) == "AGRESIVO"
    with pytest.raises(ValueError, match="todas"):
        c.perfil_desde_quiz([1, 4])


def test_hu06_evaluar_alerta():
    assert c.evaluar_alerta("VARIACION_PORCENTUAL", 3, 100, 4.02)
    assert not c.evaluar_alerta("VARIACION_PORCENTUAL", 3, 100, -1.2)
    assert c.evaluar_alerta("VARIACION_PORCENTUAL", 3, 100, -3.5)
    assert c.evaluar_alerta("PRECIO_LIMITE", 200, 212.4, 0)


def test_metricas_auxiliares():
    assert c.max_drawdown([100, 120, 90, 130]) == pytest.approx(-25)
    assert c.correlacion([1, 2, 3], [2, 4, 6]) == pytest.approx(1)
    assert c.correlacion([1], [1]) is None
    assert c.volatilidad([0.01]) == 0
    assert c.nivel_volatilidad(10) == "Baja" and c.nivel_volatilidad(25) == "Media" and c.nivel_volatilidad(40) == "Alta"
    assert c.beta([0.1], [0]) is None
    assert c.retorno_esperado_capm(None, [0.01]) is None
    assert c.retorno_esperado_capm(1.0, [0.001] * 10) == pytest.approx(25.2)
