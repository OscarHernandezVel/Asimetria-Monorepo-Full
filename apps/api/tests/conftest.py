import os
import uuid

# Configuración de prueba ANTES de importar la app: BD en memoria, caché en memoria, bcrypt rápido.
os.environ.update(DATABASE_URL="sqlite://", REDIS_URL="", JWT_SECRET="secreto-de-prueba", BCRYPT_ROUNDS="4",
                  MODO_DEMO="1", TAREAS_EN_API="0", SERVIR_WEB="0")

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import cache, proveedores  # noqa: E402

proveedores._proveedor = proveedores.ProveedorSimulado(prob_fallo=0)  # sin fallos aleatorios en pruebas

from app.main import app  # noqa: E402


@pytest.fixture(scope="session")
def client():
    with TestClient(app) as c:  # ejecuta el lifespan: crea tablas y carga 5 años de datos
        yield c


@pytest.fixture(autouse=True)
def cache_limpia():
    cache.cliente._d.clear()
    yield


@pytest.fixture
def registrar(client):
    """Crea un usuario nuevo y devuelve (cabeceras, datos)."""
    def _crear(perfil="MODERADO", **extra):
        email = f"u{uuid.uuid4().hex[:10]}@example.com"
        r = client.post("/api/v1/auth/registro", json={"nombre": "Usuario Prueba", "email": email,
                                                     "password": "Prueba2026x", "perfil": perfil, **extra})
        assert r.status_code == 201, r.text
        return {"Authorization": f"Bearer {r.json()['access_token']}"}, {**r.json()["usuario"], "email": email}
    return _crear


@pytest.fixture
def auth(registrar):
    return registrar()[0]
