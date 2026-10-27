"""Trazabilidad de llamadas externas, cuota de Alpha Vantage, message-id de SendGrid y procedencia de los datos."""
import json

import httpx
import pytest
from sqlalchemy import select

from app import servicios
from app.config import settings
from app.db import SessionLocal
from app.models import LlamadaExterna, Notificacion, PrecioHistorico
from app.proveedores import LimiteProveedor, ProveedorAlphaVantage
from app.routers import integraciones

V1 = "/api/v1"


def _av(respuesta: dict, status_code: int = 200):
    return ProveedorAlphaVantage("clave-secreta", httpx.Client(transport=httpx.MockTransport(
        lambda r: httpx.Response(status_code, json=respuesta))))


def _ultima(proveedor: str) -> LlamadaExterna:
    with SessionLocal() as db:
        return db.scalars(select(LlamadaExterna).where(LlamadaExterna.proveedor == proveedor)
                          .order_by(LlamadaExterna.id.desc())).first()


def test_cada_llamada_a_alphavantage_queda_trazada_sin_la_clave(client):
    _av({"Global Quote": {"05. price": "10"}}).cotizaciones({"AAPL": 1})
    ok = _ultima("ALPHAVANTAGE")
    assert ok.exito and ok.http_status == 200 and ok.operacion == "GLOBAL_QUOTE" and ok.latencia_ms >= 0
    with pytest.raises(httpx.HTTPStatusError):
        _av({}, status_code=500).cotizaciones({"AAPL": 1})
    fallo = _ultima("ALPHAVANTAGE")
    assert not fallo.exito and fallo.http_status == 500
    assert "clave-secreta" not in fallo.detalle and "***" in fallo.detalle  # la URL del error va enmascarada


def test_cuota_diaria_local_corta_antes_de_exceder_el_plan(client, monkeypatch):
    monkeypatch.setattr(settings, "av_limite_diario", 2)
    p = _av({"Global Quote": {"05. price": "10"}})
    p.cotizaciones({"AAPL": 1, "MSFT": 1})
    assert ProveedorAlphaVantage.cuota_usada_hoy() == 2
    with pytest.raises(LimiteProveedor, match="Cuota diaria"):
        p.cotizaciones({"AAPL": 1})
    assert ProveedorAlphaVantage.cuota_usada_hoy() == 2 and "no se llamó" in _ultima("ALPHAVANTAGE").detalle


def test_sendgrid_devuelve_message_id_y_sandbox_no_entrega(client, monkeypatch):
    monkeypatch.setattr(settings, "sendgrid_key", "SG.prueba")
    enviados = []

    def manejar(r):
        enviados.append(json.loads(r.content))
        return httpx.Response(202, headers={"X-Message-Id": "abc123"})

    http = httpx.Client(transport=httpx.MockTransport(manejar))
    assert servicios.enviar_sendgrid("a@example.com", "s", "t", http=http) == "abc123"
    assert "mail_settings" not in enviados[0]
    servicios.enviar_sendgrid("a@example.com", "s", "t", http=http, sandbox=True)
    assert enviados[1]["mail_settings"] == {"sandbox_mode": {"enable": True}}
    traza = _ultima("SENDGRID")
    assert traza.exito and traza.operacion == "mail.send (sandbox)" and "abc123" in traza.detalle


def test_notificacion_guarda_estado_y_message_id(client, auth, monkeypatch):
    monkeypatch.setattr(servicios, "enviar_notificacion", lambda u, t, c, http=None: "msg-777")
    client.post(f"{V1}/alertas", headers=auth, json={"ticker": "^NDX", "tipo": "PRECIO_LIMITE", "umbral": 1})
    client.post(f"{V1}/alertas/evaluar", headers=auth)
    n = client.get(f"{V1}/notificaciones", headers=auth).json()[0]
    assert n["estado_envio"] == "ENVIADO_CORREO" and n["proveedor_id"] == "msg-777"


def test_cada_precio_guarda_su_procedencia(client):
    with SessionLocal() as db:
        fuentes = set(db.scalars(select(PrecioHistorico.fuente).distinct()))
        con_ingesta = db.scalar(select(PrecioHistorico.fecha_ingesta).limit(1))
    assert "SIMULADO" in fuentes and con_ingesta is not None


def test_endpoint_integraciones_y_prueba(client, auth, monkeypatch):
    r = client.get(f"{V1}/integraciones", headers=auth).json()
    av = next(p for p in r["proveedores"] if p["proveedor"] == "ALPHAVANTAGE")
    assert {"configurado", "cuota_usada_hoy", "cuota_limite_diaria", "llamadas_24h", "latencia_p95_ms"} <= av.keys()
    assert any(o["fuente"] == "SIMULADO" for o in r["origen_precios"]) and isinstance(r["recientes"], list)
    assert "clave" not in json.dumps(r).lower() or "clave-secreta" not in json.dumps(r)

    monkeypatch.setattr(settings, "alphavantage_key", "")
    monkeypatch.setattr(settings, "sendgrid_key", "")
    sin = client.post(f"{V1}/integraciones/probar", headers=auth).json()
    assert not sin["ALPHAVANTAGE"]["ok"] and "No configurado" in sin["SENDGRID"]["detalle"]

    monkeypatch.setattr(settings, "alphavantage_key", "x")
    monkeypatch.setattr(settings, "sendgrid_key", "SG.x")
    monkeypatch.setattr(integraciones.ProveedorAlphaVantage, "_get", lambda self, **p: {"Global Quote": {"05. price": "500"}})
    monkeypatch.setattr(integraciones, "enviar_sendgrid", lambda *a, **k: "id")
    con = client.post(f"{V1}/integraciones/probar", headers=auth).json()
    assert con["ALPHAVANTAGE"]["ok"] and "500" in con["ALPHAVANTAGE"]["detalle"] and con["SENDGRID"]["ok"]
    assert client.get(f"{V1}/integraciones").status_code == 401
