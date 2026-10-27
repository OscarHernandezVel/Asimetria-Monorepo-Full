"""Autenticación (RNF-03): bcrypt costo ≥ 12, JWT HS256 con expiración de 120 min, 401 sin token válido."""
from datetime import datetime, timedelta, timezone

import bcrypt
import jwt
from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from .config import settings
from .db import get_db
from .models import Usuario

_bearer = HTTPBearer(auto_error=False)


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt(settings.bcrypt_rounds)).decode()


def verificar_password(password: str, hashed: str) -> bool:
    try:
        return bcrypt.checkpw(password.encode(), hashed.encode())
    except ValueError:
        return False


# Hash fijo para igualar el tiempo de respuesta cuando el correo no existe (evita enumerar usuarios).
HASH_FICTICIO = hash_password("contraseña-ficticia-para-tiempo-constante")


def crear_token(usuario_id: int) -> tuple[str, datetime]:
    exp = datetime.now(timezone.utc) + timedelta(minutes=settings.jwt_minutos)
    token = jwt.encode({"sub": str(usuario_id), "exp": exp, "iat": datetime.now(timezone.utc)},
                       settings.jwt_secret, algorithm="HS256")
    return token, exp


def _no_autorizado(detalle: str) -> HTTPException:
    return HTTPException(status.HTTP_401_UNAUTHORIZED, detalle, headers={"WWW-Authenticate": "Bearer"})


def usuario_actual(cred: HTTPAuthorizationCredentials | None = Depends(_bearer), db: Session = Depends(get_db)) -> Usuario:
    if cred is None:
        raise _no_autorizado("No autenticado")
    try:
        datos = jwt.decode(cred.credentials, settings.jwt_secret, algorithms=["HS256"])
    except jwt.ExpiredSignatureError:
        raise _no_autorizado("La sesión expiró")
    except jwt.InvalidTokenError:
        raise _no_autorizado("Token inválido")
    usuario = db.get(Usuario, int(datos["sub"]))
    if usuario is None:
        raise _no_autorizado("Token inválido")
    return usuario


def ip_cliente(request: Request) -> str | None:
    """IP de origen para auditoría. Nginx fija X-Real-IP con $remote_addr (no la controla el cliente);
    la API solo debe publicarse detrás del proxy para que este encabezado sea confiable."""
    return request.headers.get("x-real-ip") or (request.client.host if request.client else None)
