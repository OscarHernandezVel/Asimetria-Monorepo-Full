"""HU-05, HU-06 y HU-08 contra la API."""
from datetime import date

import pytest

from app import cache, servicios
from app.db import SessionLocal

V1 = "/api/v1"


def test_hu08_crud_watchlist_y_duplicados(client, auth):
    assert client.get(f"{V1}/watchlist", headers=auth).json() == []
    r = client.post(f"{V1}/watchlist", headers=auth, json={"ticker": "rtc"})
    assert r.status_code == 201 and r.json()["ticker"] == "RTC"  # CA-1
    dup = client.post(f"{V1}/watchlist", headers=auth, json={"ticker": "RTC"})
    assert dup.status_code == 409 and "ya está en tu lista" in dup.json()["detail"]  # CA-4
    p = client.patch(f"{V1}/watchlist/RTC", headers=auth, json={"cantidad": 120, "favorito": True}).json()
    assert p["cantidad"] == 120 and p["favorito"] is True  # CA-2
    assert client.patch(f"{V1}/watchlist/RTC", headers=auth, json={"cantidad": -1}).status_code == 422
    assert client.delete(f"{V1}/watchlist/RTC", headers=auth).status_code == 204
    assert client.get(f"{V1}/watchlist", headers=auth).json() == []
    assert client.delete(f"{V1}/watchlist/RTC", headers=auth).status_code == 404
    assert client.post(f"{V1}/watchlist", headers=auth, json={"ticker": "NOPE"}).status_code == 404


def test_hu08_ca3_privacidad_entre_usuarios(client, registrar):
    a, _ = registrar()
    b, _ = registrar()
    client.post(f"{V1}/watchlist", headers=a, json={"ticker": "NVC"})
    assert client.get(f"{V1}/watchlist", headers=b).json() == []
    assert client.delete(f"{V1}/watchlist/NVC", headers=b).status_code == 404
    assert len(client.get(f"{V1}/watchlist", headers=a).json()) == 1


def _dias_referencia(client, h):
    d = client.get(f"{V1}/metrics/RTC/analisis", headers=h).json()["desglose"]
    fechas = client.get(f"{V1}/metrics/RTC/analisis", headers=h).json()["fechas"]
    return {k: fechas[fechas.index(d[k]["fecha"]) - 1:fechas.index(d[k]["fecha"]) + 1] for k in ("alcista", "bajista")}


@pytest.mark.parametrize("caso,esperado", [("alcista", 1040), ("bajista", 975)])
def test_hu05_ca1_ca2_ritech_1000_usd(client, auth, caso, esperado):
    ini, fin = _dias_referencia(client, auth)[caso]
    r = client.post(f"{V1}/simulaciones", headers=auth, json={"ticker": "RTC", "monto": 1000, "fecha_inicio": ini, "fecha_fin": fin})
    assert r.status_code == 201
    assert r.json()["valor_final"] == pytest.approx(esperado, abs=0.05)
    assert set(r.json()["serie"][0]) == {"fecha", "portafolio", "indice"}  # CA-3
    hist = client.get(f"{V1}/simulaciones", headers=auth).json()
    assert hist[0]["ticker"] == "RTC" and hist[0]["monto"] == 1000


@pytest.mark.parametrize("cuerpo,msg", [
    ({"monto": 0}, "mayor que 0"), ({"monto": -10}, "mayor que 0"),
    ({"fecha_inicio": "1990-01-01", "fecha_fin": "1990-02-01"}, "No hay datos"),
    ({"fecha_inicio": "2025-02-01", "fecha_fin": "2025-01-01"}, "posterior"),
])
def test_hu05_ca4_datos_invalidos(client, auth, cuerpo, msg):
    base = {"ticker": "RTC", "monto": 1000, "fecha_inicio": "2024-01-02", "fecha_fin": date.today().isoformat()}
    r = client.post(f"{V1}/simulaciones", headers=auth, json={**base, **cuerpo})
    assert r.status_code == 422 and msg in r.json()["detail"]


def test_hu06_crear_disparar_y_no_disparar(client, auth):
    with SessionLocal() as db:
        servicios.sincronizar_cotizaciones(db, dormir=lambda s: None)
    sube = client.post(f"{V1}/alertas", headers=auth, json={"ticker": "RTC", "tipo": "VARIACION_PORCENTUAL", "umbral": 0.0001})
    nunca = client.post(f"{V1}/alertas", headers=auth, json={"ticker": "RTC", "tipo": "VARIACION_PORCENTUAL", "umbral": 90})
    assert sube.status_code == nunca.status_code == 201 and sube.json()["estado"] == "ACTIVA"  # CA-1
    assert client.post(f"{V1}/alertas", headers=auth, json={"ticker": "RTC", "tipo": "OTRO", "umbral": 1}).status_code == 422
    assert client.post(f"{V1}/alertas", headers=auth, json={"ticker": "RTC", "tipo": "PRECIO_LIMITE", "umbral": 0}).status_code == 422
    r = client.post(f"{V1}/alertas/evaluar", headers=auth).json()
    assert r == {"disparadas": 1, "pendientes": 0}
    estados = {a["umbral"]: a["estado"] for a in client.get(f"{V1}/alertas", headers=auth).json()}
    assert estados == {0.0001: "DISPARADA", 90: "ACTIVA"}  # CA-2 y CA-3
    assert "RiTech" in client.get(f"{V1}/notificaciones", headers=auth).json()[0]["texto"]


def test_hu06_ca4_correo_caido_no_pierde_la_alerta(client, auth):
    client.post(f"{V1}/alertas", headers=auth, json={"ticker": "^NDX", "tipo": "PRECIO_LIMITE", "umbral": 1})
    cache.set_json("sim:correo_caido", True)
    assert client.post(f"{V1}/alertas/evaluar", headers=auth).json() == {"disparadas": 0, "pendientes": 1}
    assert client.get(f"{V1}/alertas", headers=auth).json()[0]["pendiente_envio"] is True
    cache.set_json("sim:correo_caido", False)
    assert client.post(f"{V1}/alertas/evaluar", headers=auth).json() == {"disparadas": 1, "pendientes": 0}
    assert client.get(f"{V1}/alertas", headers=auth).json()[0]["pendiente_envio"] is False


def test_alertas_estado_borrado_y_privacidad(client, registrar):
    a, _ = registrar()
    b, _ = registrar()
    id_ = client.post(f"{V1}/alertas", headers=a, json={"ticker": "QFN", "tipo": "PRECIO_LIMITE", "umbral": 999}).json()["id"]
    assert client.patch(f"{V1}/alertas/{id_}", headers=b, json={"estado": "INACTIVA"}).status_code == 404
    assert client.delete(f"{V1}/alertas/{id_}", headers=b).status_code == 404
    assert client.patch(f"{V1}/alertas/{id_}", headers=a, json={"estado": "INACTIVA"}).json()["estado"] == "INACTIVA"
    assert client.delete(f"{V1}/alertas/{id_}", headers=a).status_code == 204
    assert client.get(f"{V1}/alertas", headers=a).json() == []
