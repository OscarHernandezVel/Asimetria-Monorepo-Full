"""Autenticación y perfilamiento (HU-07)."""
from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import cache, calculos
from ..auditoria import registrar
from ..config import settings
from ..db import get_db
from ..models import Usuario
from ..schemas import LoginIn, PlanIn, QuizIn, RegistroIn, TokenOut, UsuarioOut
from ..security import (HASH_FICTICIO, crear_token, hash_password, ip_cliente, usuario_actual,
                        verificar_password)
from ..servicios import crear_usuario_demo

router = APIRouter(tags=["Autenticación y perfil"])
MAX_INTENTOS, BLOQUEO_S = 5, 60


def _token(u: Usuario) -> TokenOut:
    tok, exp = crear_token(u.id)
    return TokenOut(access_token=tok, expira=exp, usuario=UsuarioOut.model_validate(u))


@router.post("/auth/registro", response_model=TokenOut, status_code=201)
def registro(datos: RegistroIn, request: Request, db: Session = Depends(get_db)):
    email = datos.email.lower()
    if db.scalar(select(Usuario).where(Usuario.email == email)):  # CA-5
        registrar(db, "REGISTRO", "ERROR", email=email, ip=ip_cliente(request), detalle="correo en uso")
        raise HTTPException(status.HTTP_409_CONFLICT, "Este correo ya está en uso. Inicia sesión o usa otro correo.")
    u = Usuario(email=email, nombre=datos.nombre, perfil_riesgo=datos.perfil, password_hash=hash_password(datos.password))
    db.add(u)
    db.commit()
    registrar(db, "REGISTRO", "EXITO", u, ip_cliente(request), f"perfil {datos.perfil or 'pendiente'}")
    return _token(u)


@router.post("/auth/login", response_model=TokenOut)
def login(datos: LoginIn, request: Request, db: Session = Depends(get_db)):
    email, ip = datos.email.lower(), ip_cliente(request)
    clave = f"login:{email}"
    if (cache.get_json(clave) or 0) >= MAX_INTENTOS:
        registrar(db, "INICIO_SESION", "ERROR", email=email, ip=ip, detalle="bloqueado por intentos")
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Demasiados intentos. Espera un minuto e inténtalo de nuevo.")
    u = db.scalar(select(Usuario).where(Usuario.email == email))
    if not verificar_password(datos.password, u.password_hash if u else HASH_FICTICIO) or u is None:
        cache.contador(clave, BLOQUEO_S)
        registrar(db, "INICIO_SESION", "ERROR", email=email, ip=ip, detalle="credenciales inválidas")
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Credenciales inválidas")  # CA-4: mensaje genérico
    cache.set_json(clave, 0, ttl=1)
    registrar(db, "INICIO_SESION", "EXITO", u, ip)
    return _token(u)


@router.post("/auth/demo", response_model=TokenOut, include_in_schema=settings.modo_demo)
def demo(request: Request, db: Session = Depends(get_db)):
    """Sesión de demostración (solo con MODO_DEMO=1)."""
    if not settings.modo_demo:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No encontrado")
    u = crear_usuario_demo(db)
    registrar(db, "INICIO_SESION", "EXITO", u, ip_cliente(request), "cuenta demo")
    return _token(u)


@router.post("/auth/logout", status_code=204)
def logout(request: Request, u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    """Registra el cierre de sesión (RNF-04). El JWT es sin estado: el cliente lo descarta y expira solo."""
    registrar(db, "CIERRE_SESION", "EXITO", u, ip_cliente(request))
    return Response(status_code=204)


@router.get("/usuarios/me", response_model=UsuarioOut)
def yo(u: Usuario = Depends(usuario_actual)):
    return u


@router.post("/usuarios/me/quiz", response_model=UsuarioOut)
def quiz(datos: QuizIn, request: Request, u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    try:
        perfil = calculos.perfil_desde_quiz(datos.respuestas)
    except ValueError as e:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(e))
    anterior = u.perfil_riesgo
    u.perfil_riesgo = perfil
    db.commit()
    registrar(db, "CAMBIO_PERFIL_RIESGO", "EXITO", u, ip_cliente(request), f"{anterior or 'ninguno'} → {perfil}")
    return u


@router.put("/usuarios/me/plan", response_model=UsuarioOut)
def plan(datos: PlanIn, request: Request, u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    """Activa un plan. La pasarela de pago (tokenización PCI DSS) está fuera de alcance: no se recibe ningún dato de tarjeta."""
    u.plan = datos.plan
    db.commit()
    registrar(db, "SUSCRIPCION", "EXITO", u, ip_cliente(request), f"plan {datos.plan}")
    return u
