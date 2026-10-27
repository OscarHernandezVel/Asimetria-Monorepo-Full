"""HU-02, HU-03, HU-04, HU-09, HU-10 y RNF-01 (caché) contra la API real con 5 años de datos simulados."""
import pytest

from app import cache, servicios
from app.db import SessionLocal

V1 = "/api/v1"


def test_hu02_ca1_cinco_anios_de_historia(client, auth):
    empresas = client.get(f"{V1}/mercado/empresas", headers=auth).json()
    assert {"^NDX", "RTC", "HLX"} <= {e["ticker"] for e in empresas}
    serie = client.get(f"{V1}/mercado/precios/^NDX", headers=auth, params={"dias": 5000}).json()
    assert len(serie["cierres"]) >= 1260
    assert client.get(f"{V1}/mercado/precios/RTC", headers=auth, params={"dias": 20}).json()["cierres"].__len__() == 21
    assert client.get(f"{V1}/mercado/precios/NOPE", headers=auth).status_code == 404


def test_hu03_metricas_ritech_y_datos_insuficientes(client, auth):
    ms = {m["ticker"]: m for m in client.get(f"{V1}/metrics/", headers=auth).json()}
    rt = ms["RTC"]
    assert rt["beta_alcista"] == pytest.approx(2.0, abs=0.1) and rt["beta_bajista"] == pytest.approx(1.25, abs=0.1)
    assert rt["ratio_asimetria"] > 1 and rt["error"] is None and len(rt["tendencia"]) == 21
    assert ms["HLX"]["beta_alcista"] is None and ms["HLX"]["error"] == "Datos insuficientes para calcular la beta"  # CA-4
    assert "^NDX" not in ms
    assert client.get(f"{V1}/metrics/RTC", headers=auth).json()["ticker"] == "RTC"
    assert client.get(f"{V1}/metrics/NOPE", headers=auth).status_code == 404


def test_rnf01_metricas_se_sirven_desde_cache(client, auth, monkeypatch):
    client.get(f"{V1}/metrics/", headers=auth)
    assert cache.get_json("metrics:all") is not None
    monkeypatch.setattr(servicios, "cierres", lambda *a, **k: (_ for _ in ()).throw(AssertionError("no debe recomputar")))
    assert client.get(f"{V1}/metrics/", headers=auth).status_code == 200


def test_hu09_ca2_desglose_dia_alcista_y_bajista(client, auth):
    a = client.get(f"{V1}/metrics/RTC/analisis", headers=auth).json()
    up, down = a["desglose"]["alcista"], a["desglose"]["bajista"]
    assert up["indice"] == pytest.approx(2.0, abs=0.01) and up["activo"] == pytest.approx(4.0, abs=0.01) and up["ratio"] == pytest.approx(2.0, abs=0.01)
    assert down["indice"] == pytest.approx(-2.0, abs=0.01) and down["activo"] == pytest.approx(-2.5, abs=0.01) and down["ratio"] == pytest.approx(1.25, abs=0.01)
    assert a["ventana"] == 90 and len(a["base100_activo"]) == 91 and a["base100_indice"][0] == pytest.approx(100)
    assert a["alfa"] == pytest.approx(a["retorno"] - a["retorno_indice"]) and a["max_drawdown"] <= 0
    assert a["metricas"]["ticker"] == "RTC"
    assert client.get(f"{V1}/metrics/^NDX/analisis", headers=auth).status_code == 422


def test_hu04_ca5_sin_perfil_pide_quiz(client, registrar):
    h, _ = registrar(perfil=None)
    r = client.get(f"{V1}/recommendations/", headers=h)
    assert r.status_code == 409 and "quiz" in r.json()["detail"]


def test_hu04_ca1_ca3_conservador_dentro_de_su_margen_y_ordenado(client, registrar):
    h, _ = registrar(perfil="CONSERVADOR")
    reco = client.get(f"{V1}/recommendations/", headers=h).json()
    assert reco and all(x["volatilidad"] <= 20 for x in reco)
    assert [x["puntaje"] for x in reco] == sorted((x["puntaje"] for x in reco), reverse=True)
    assert all(x["beta_alcista"] is not None for x in reco)


def test_hu04_ca2_ca4_filtros_y_sin_resultados(client, registrar):
    h, _ = registrar(perfil="AGRESIVO")
    todos = client.get(f"{V1}/recommendations/", headers=h).json()
    filtrados = client.get(f"{V1}/recommendations/", headers=h, params={"vol_max": 25, "retorno_min": 5}).json()
    assert len(filtrados) < len(todos)
    assert all(x["volatilidad"] <= 25 and x["retorno_esperado"] >= 5 for x in filtrados)
    assert client.get(f"{V1}/recommendations/", headers=h, params={"retorno_min": 999}).json() == []


def test_destacado_publico_para_landing(client):
    d = client.get(f"{V1}/destacado").json()
    assert d["ticker"] == "RTC" and len(d["base100_activo"]) == 41 and d["nivel_volatilidad"] in {"Baja", "Media", "Alta"}


def test_hu09_ca4_sin_datos_en_vivo_usa_ultimo_cierre(client, auth):
    r = client.get(f"{V1}/mercado/cotizaciones", headers=auth).json()
    assert r["estado"]["desactualizado"] is True
    with SessionLocal() as db:
        _, c = servicios.cierres(db, servicios.empresa(db, "RTC").id, 1)
    assert r["cotizaciones"]["RTC"]["precio"] == pytest.approx(c[-1])


def test_hu01_ca2_sincronizacion_publica_precios_en_vivo(client, auth):
    with SessionLocal() as db:
        estado = servicios.sincronizar_cotizaciones(db, dormir=lambda s: None)
    assert estado["desactualizado"] is False
    r = client.get(f"{V1}/mercado/cotizaciones", headers=auth).json()
    assert r["estado"]["desactualizado"] is False and r["cotizaciones"]["^NDX"]["precio"] > 0
