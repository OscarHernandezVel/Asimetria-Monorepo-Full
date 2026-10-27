"""RNF-05, RNF-06, HU-01 (proveedor externo), HU-02 CA-3, RNF-04 (retención) y consistencia del esquema."""
import re
import time
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import httpx
import pytest
from sqlalchemy import func, select

from app import cache, servicios
from app.auditoria import purgar
from app.cache import CacheMemoria
from app.db import Base, SessionLocal
from app.main import app
from app.models import Auditoria, PrecioHistorico
from app.proveedores import (CircuitoAbierto, ErrorProveedor, LimiteProveedor, ProveedorAlphaVantage, ProveedorSimulado,
                             con_resiliencia)

V1 = "/api/v1"


def test_resiliencia_reintenta_con_backoff_y_abre_el_circuito():
    esperas, llamadas = [], []

    def falla():
        llamadas.append(1)
        raise ErrorProveedor("caído")

    for _ in range(3):
        with pytest.raises(ErrorProveedor):
            con_resiliencia(falla, dormir=esperas.append)
    assert len(llamadas) == 9 and esperas[:2] == [0.15, 0.3]  # 3 intentos por ciclo, backoff exponencial
    with pytest.raises(CircuitoAbierto):
        con_resiliencia(lambda: "ok", dormir=esperas.append)
    assert len(llamadas) == 9  # con el circuito abierto no se llama al proveedor


def test_resiliencia_exito_tras_un_fallo():
    estado = {"n": 0}

    def a_veces():
        estado["n"] += 1
        if estado["n"] == 1:
            raise LimiteProveedor("cuota")
        return "ok"

    assert con_resiliencia(a_veces, dormir=lambda s: None) == "ok"


def test_rnf06_proveedor_caido_mantiene_ultimo_dato_sin_500(client, auth):
    with SessionLocal() as db:
        servicios.sincronizar_cotizaciones(db, dormir=lambda s: None)
        previo = client.get(f"{V1}/mercado/cotizaciones", headers=auth).json()
        assert client.put(f"{V1}/demo/fallos", headers=auth, json={"proveedor_caido": True}).status_code == 200
        assert client.get(f"{V1}/demo/fallos", headers=auth).json()["proveedor_caido"] is True
        estado = servicios.sincronizar_cotizaciones(db, dormir=lambda s: None)
    assert estado["desactualizado"] is True and estado["ultima_actualizacion"] == previo["estado"]["ultima_actualizacion"]
    r = client.get(f"{V1}/mercado/cotizaciones", headers=auth)
    assert r.status_code == 200 and r.json()["estado"]["desactualizado"] is True
    assert r.json()["cotizaciones"]["RTC"]["precio"] == previo["cotizaciones"]["RTC"]["precio"]


def test_rnf05_health(client):
    r = client.get("/health")
    assert r.status_code == 200 and r.json()["db"] is True and r.json()["cache"] is True


def test_errores_internos_no_filtran_detalles(client, auth, monkeypatch):
    monkeypatch.setattr(servicios, "metricas_listado", lambda db: 1 / 0)
    from fastapi.testclient import TestClient
    with TestClient(app, raise_server_exceptions=False) as c:
        r = c.get(f"{V1}/metrics/", headers=auth)
    assert r.status_code == 500 and "ZeroDivision" not in r.text and r.headers["x-content-type-options"] == "nosniff"


def test_cache_memoria_ttl_contador_e_invalidacion():
    c = CacheMemoria()
    c.set("a:1", "x", ex=1)
    c.set("a:2", "y")
    assert c.get("a:1") == "x" and c.incr("n") == 1 and c.incr("n") == 2
    c.expire("n", 1)
    c._d["a:1"] = ("x", time.time() - 1)
    assert c.get("a:1") is None and sorted(c.scan_iter("a:*")) == ["a:2"]
    cache.set_json("metrics:x", [1])
    cache.invalidar("metrics:")
    assert cache.get_json("metrics:x") is None and cache.contador("k", 5) == 1


def _av(respuestas: dict):
    def manejar(req: httpx.Request):
        assert req.url.params["apikey"] == "clave"
        return httpx.Response(200, json=respuestas[req.url.params["function"]])
    return ProveedorAlphaVantage("clave", httpx.Client(transport=httpx.MockTransport(manejar)))


def test_hu01_alphavantage_historico_cotizacion_y_dividendos():
    serie = {f"2026-01-0{d}": {"1. open": "10", "2. high": "11", "3. low": "9", "4. close": str(d), "5. volume": "100"}
             for d in range(2, 8)}
    p = _av({"TIME_SERIES_DAILY": {"Time Series (Daily)": serie},
             "GLOBAL_QUOTE": {"Global Quote": {"05. price": "123.45"}}, "OVERVIEW": {"DividendYield": "0.0123"}})
    barras = p.historico("AAPL", dias=2)
    assert [b.cierre for b in barras] == [5, 6, 7] and barras[0].fecha == date(2026, 1, 5)
    assert p.cotizaciones({"AAPL": 1, "^NDX": 1}) == {"AAPL": 123.45, "^NDX": 123.45}
    assert p.dividendos("AAPL") == pytest.approx(1.23) and p.dividendos("^NDX") is None
    assert p.catalogo()[0][0] == "^NDX"


def test_hu01_ca3_limite_y_errores_del_proveedor():
    with pytest.raises(LimiteProveedor):
        _av({"GLOBAL_QUOTE": {"Note": "rate limit"}}).cotizaciones({"AAPL": 1})
    with pytest.raises(ErrorProveedor):
        _av({"GLOBAL_QUOTE": {"Error Message": "símbolo inválido"}}).cotizaciones({"AAPL": 1})
    with pytest.raises(ValueError):
        ProveedorAlphaVantage("")


def test_hu06_envio_por_sendgrid(monkeypatch):
    enviados = []
    cliente = httpx.Client(transport=httpx.MockTransport(lambda r: enviados.append(r) or httpx.Response(202)))
    monkeypatch.setattr(servicios.settings, "sendgrid_key", "SG.prueba")
    u = type("U", (), {"email": "a@example.com"})()
    servicios.enviar_notificacion(u, "hola", "CORREO", http=cliente)
    assert enviados[0].headers["authorization"] == "Bearer SG.prueba"
    falla = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(500)))
    with pytest.raises(servicios.ErrorNotificacion):
        servicios.enviar_notificacion(u, "hola", "CORREO", http=falla)


def test_hu02_ca2_ca3_cierre_diario_sin_duplicados(client):
    lunes = date(2031, 1, 6)
    prov = ProveedorSimulado(prob_fallo=0)
    with SessionLocal() as db:
        servicios.sincronizar_cotizaciones(db, prov, dormir=lambda s: None)
        primero = servicios.cierre_diario(db, prov, hoy=lunes)
        segundo = servicios.cierre_diario(db, prov, hoy=lunes)
        assert servicios.cierre_diario(db, prov, hoy=date(2031, 1, 4)) == 0  # sábado
        n = db.scalar(select(func.count()).select_from(PrecioHistorico).where(PrecioHistorico.time >= datetime(2031, 1, 6, tzinfo=timezone.utc)))
        db.execute(PrecioHistorico.__table__.delete().where(PrecioHistorico.time >= datetime(2031, 1, 1, tzinfo=timezone.utc)))
        db.commit()
    assert primero == 12 and segundo == 0 and n == 12


def test_rnf04_retencion_90_dias(client):
    with SessionLocal() as db:
        db.add(Auditoria(ts=datetime.now(timezone.utc) - timedelta(days=91), evento="VIEJO", estado="EXITO"))
        db.commit()
        assert purgar(db) >= 1
        assert db.scalar(select(Auditoria).where(Auditoria.evento == "VIEJO")) is None


def test_modelos_coinciden_con_schema_sql():
    sql = (Path(__file__).resolve().parents[3] / "packages" / "db" / "schema.sql").read_text(encoding="utf8")
    for tabla in Base.metadata.sorted_tables:
        bloque = re.search(rf"CREATE TABLE {tabla.name} \((.*?)\n\);", sql, re.S)
        assert bloque, f"falta la tabla {tabla.name} en schema.sql"
        for col in tabla.columns:
            assert re.search(rf"^\s+{col.name}\s", bloque.group(1), re.M), f"falta {tabla.name}.{col.name} en schema.sql"


def test_tareas_celery_ejecutan_los_servicios(client):
    from app import worker
    assert worker.sincronizar_cotizaciones()["desactualizado"] is False
    assert worker.evaluar_alertas().keys() == {"disparadas", "pendientes"}
    assert worker.purgar_auditoria() >= 0
    assert set(worker.celery.conf.beat_schedule) == {"cotizaciones", "alertas", "cierre-diario", "purga-auditoria"}


def test_cache_caida_degrada_sin_bloquear(monkeypatch):
    class Roto:
        def __getattr__(self, _):
            def falla(*a, **k):
                raise ConnectionError("redis caído")
            return falla
    monkeypatch.setattr(cache, "cliente", Roto())
    monkeypatch.setattr(cache, "_caida_hasta", 0.0)
    assert cache.get_json("x") is None and not cache._disponible()  # primer fallo abre el circuito
    cache.set_json("x", 1)
    cache.invalidar("x")
    assert cache.get_raw("x") is None and cache.contador("k", 5) == 0 and cache.ping() is False
    monkeypatch.setattr(cache, "_caida_hasta", 0.0)
    for op in (lambda: cache.set_json("x", 1), lambda: cache.invalidar("x"), lambda: cache.get_raw("x"), lambda: cache.contador("k", 1)):
        monkeypatch.setattr(cache, "_caida_hasta", 0.0)
        op()
        assert not cache._disponible()


def test_hu01_ca1_la_api_key_no_aparece_en_los_logs(caplog):
    import logging
    caplog.set_level(logging.DEBUG)
    logging.getLogger("httpx").setLevel(logging.WARNING)  # lo que fija app.main al arrancar
    p = _av({"GLOBAL_QUOTE": {"Global Quote": {"05. price": "1"}}})
    p.cotizaciones({"AAPL": 1})
    with pytest.raises(LimiteProveedor):
        _av({"GLOBAL_QUOTE": {"Note": "límite"}}).cotizaciones({"AAPL": 1})
    assert "apikey=" not in caplog.text  # la clave de prueba ("clave") viaja en la URL, nunca en el log


def test_jwt_secret_obligatorio_fuera_de_desarrollo(monkeypatch):
    from app.config import Settings
    monkeypatch.delenv("JWT_SECRET", raising=False)
    monkeypatch.setenv("DATABASE_URL", "postgresql+psycopg://u:p@db/asimetria")
    with pytest.raises(RuntimeError, match="JWT_SECRET"):
        Settings()
    monkeypatch.setenv("DATABASE_URL", "sqlite://")
    assert len(Settings().jwt_secret) >= 32  # en desarrollo se genera uno temporal
