"""HU-07, RNF-03 (bcrypt, JWT, 401) y RNF-04 (auditoría)."""
from datetime import datetime, timedelta, timezone

import jwt
import pytest
from sqlalchemy import select

from app.config import settings
from app.db import SessionLocal
from app.models import Auditoria, Usuario

V1 = "/api/v1"


def test_ca1_registro_guarda_hash_bcrypt_nunca_texto_plano(client, registrar):
    _, u = registrar()
    with SessionLocal() as db:
        guardado = db.scalar(select(Usuario).where(Usuario.email == u["email"]))
    assert guardado.password_hash.startswith("$2b$") and "Prueba2026x" not in guardado.password_hash


def test_ca5_correo_ya_registrado(client, registrar):
    _, u = registrar()
    r = client.post(f"{V1}/auth/registro", json={"nombre": "X", "email": u["email"], "password": "Otra12345"})
    assert r.status_code == 409 and "ya está en uso" in r.json()["detail"]


@pytest.mark.parametrize("password", ["corta1", "sololetras", "12345678"])
def test_registro_rechaza_contrasenas_debiles(client, password):
    r = client.post(f"{V1}/auth/registro", json={"nombre": "X", "email": "debil@example.com", "password": password})
    assert r.status_code == 422


def test_ca2_login_entrega_jwt_hs256_de_120_minutos(client, registrar):
    _, u = registrar()
    r = client.post(f"{V1}/auth/login", json={"email": u["email"], "password": "Prueba2026x"})
    assert r.status_code == 200
    datos = jwt.decode(r.json()["access_token"], settings.jwt_secret, algorithms=["HS256"])
    duracion = datos["exp"] - datos["iat"]
    assert duracion == pytest.approx(120 * 60, abs=2)


def test_ca4_credenciales_invalidas_mensaje_generico(client, registrar):
    _, u = registrar()
    mala = client.post(f"{V1}/auth/login", json={"email": u["email"], "password": "Incorrecta1"})
    inexistente = client.post(f"{V1}/auth/login", json={"email": "nadie@example.com", "password": "Incorrecta1"})
    assert mala.status_code == inexistente.status_code == 401
    assert mala.json()["detail"] == inexistente.json()["detail"] == "Credenciales inválidas"
    assert "access_token" not in mala.json()


def test_bloqueo_tras_5_intentos(client, registrar):
    _, u = registrar()
    for _ in range(5):
        client.post(f"{V1}/auth/login", json={"email": u["email"], "password": "Incorrecta1"})
    r = client.post(f"{V1}/auth/login", json={"email": u["email"], "password": "Prueba2026x"})
    assert r.status_code == 429


@pytest.mark.parametrize("metodo,ruta", [
    ("get", "/usuarios/me"), ("get", "/watchlist"), ("get", "/alertas"), ("get", "/metrics/"),
    ("get", "/recommendations/"), ("get", "/mercado/cotizaciones"), ("post", "/simulaciones"), ("get", "/auditoria"),
])
def test_rnf03_100_por_ciento_401_sin_token(client, metodo, ruta):
    r = getattr(client, metodo)(V1 + ruta)
    assert r.status_code == 401 and r.headers["www-authenticate"] == "Bearer"


def test_rnf03_token_caducado_o_alterado_401(client, registrar):
    _, u = registrar()
    caducado = jwt.encode({"sub": str(u["id"]), "exp": datetime.now(timezone.utc) - timedelta(minutes=1)},
                          settings.jwt_secret, algorithm="HS256")
    falso = jwt.encode({"sub": str(u["id"]), "exp": datetime.now(timezone.utc) + timedelta(minutes=5)}, "otro", algorithm="HS256")
    huerfano = jwt.encode({"sub": "999999", "exp": datetime.now(timezone.utc) + timedelta(minutes=5)},
                          settings.jwt_secret, algorithm="HS256")
    for tok, msg in [(caducado, "La sesión expiró"), (falso, "Token inválido"), (huerfano, "Token inválido")]:
        r = client.get(f"{V1}/usuarios/me", headers={"Authorization": f"Bearer {tok}"})
        assert r.status_code == 401 and r.json()["detail"] == msg


def test_ca3_quiz_clasifica_y_guarda_perfil(client, registrar):
    h, _ = registrar(perfil=None)
    assert client.get(f"{V1}/usuarios/me", headers=h).json()["perfil_riesgo"] is None
    r = client.post(f"{V1}/usuarios/me/quiz", headers=h, json={"respuestas": [3, 3, 3, 3, 2]})
    assert r.json()["perfil_riesgo"] == "AGRESIVO"
    assert client.post(f"{V1}/usuarios/me/quiz", headers=h, json={"respuestas": [1, 2, 3, 4, 1]}).status_code == 422
    assert client.post(f"{V1}/usuarios/me/quiz", headers=h, json={"respuestas": [1, 2]}).status_code == 422


def test_plan_y_demo(client, registrar):
    h, _ = registrar()
    assert client.put(f"{V1}/usuarios/me/plan", headers=h, json={"plan": "MODERADO"}).json()["plan"] == "MODERADO"
    demo = client.post(f"{V1}/auth/demo")
    assert demo.status_code == 200 and demo.json()["usuario"]["email"] == "demo@asimetria.app"


def test_rnf04_auditoria_registra_campos_obligatorios(client, registrar):
    h, u = registrar()
    client.post(f"{V1}/auth/login", json={"email": u["email"], "password": "Prueba2026x"}, headers={"X-Real-IP": "203.0.113.7"})
    eventos = client.get(f"{V1}/auditoria", headers=h).json()
    login = next(e for e in eventos if e["evento"] == "INICIO_SESION")
    assert login["ip"] == "203.0.113.7" and login["estado"] == "EXITO" and login["ts"] and login["email"] == u["email"]
    assert {"REGISTRO", "INICIO_SESION"} <= {e["evento"] for e in eventos}
    with SessionLocal() as db:  # los intentos fallidos también quedan (sin usuario_id)
        assert db.scalar(select(Auditoria).where(Auditoria.evento == "INICIO_SESION", Auditoria.estado == "ERROR"))


def test_logout_queda_auditado(client, registrar):
    h, _ = registrar()
    assert client.post(f"{V1}/auth/logout", headers=h).status_code == 204
    assert client.get(f"{V1}/auditoria", headers=h).json()[0]["evento"] == "CIERRE_SESION"
