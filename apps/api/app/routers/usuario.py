"""Recursos del usuario: watchlist (HU-08), simulaciones (HU-05), alertas (HU-06) y auditoría (RNF-04).
Todas las consultas filtran por el usuario del token: nadie ve datos de otro (HU-08 CA-3)."""
from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import calculos, servicios
from ..auditoria import registrar
from ..db import get_db
from ..models import Auditoria, Empresa, ListaSeguimiento, Notificacion, ReglaAlerta, SimulacionPortafolio, Usuario
from ..schemas import (AlertaIn, AlertaOut, AlertaPatch, AuditoriaOut, NotificacionOut, SimulacionHist, SimulacionIn,
                       SimulacionOut, WatchIn, WatchOut, WatchPatch)
from ..security import ip_cliente, usuario_actual

router = APIRouter(tags=["Usuario"])


def _empresa(db: Session, ticker: str) -> Empresa:
    e = servicios.empresa(db, ticker.upper())
    if e is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Empresa no encontrada")
    return e


# ---------------------------------------------------------------- watchlist / portafolio teórico
def _watch_out(item: ListaSeguimiento, e: Empresa) -> WatchOut:
    return WatchOut(ticker=e.ticker, nombre=e.nombre_empresa, sector=e.sector, cantidad=item.cantidad,
                    favorito=item.favorito, fecha_agregado=item.fecha_agregado)


def _item(db: Session, u: Usuario, ticker: str) -> tuple[ListaSeguimiento, Empresa]:
    e = _empresa(db, ticker)
    item = db.scalar(select(ListaSeguimiento).where(ListaSeguimiento.usuario_id == u.id, ListaSeguimiento.empresa_id == e.id))
    if item is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "La empresa no está en tu lista")
    return item, e


@router.get("/watchlist", response_model=list[WatchOut])
def watchlist(u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    filas = db.execute(select(ListaSeguimiento, Empresa).join(Empresa, Empresa.id == ListaSeguimiento.empresa_id)
                       .where(ListaSeguimiento.usuario_id == u.id).order_by(ListaSeguimiento.id)).all()
    return [_watch_out(i, e) for i, e in filas]


@router.post("/watchlist", response_model=WatchOut, status_code=201)
def agregar(datos: WatchIn, request: Request, u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    e = _empresa(db, datos.ticker)
    if db.scalar(select(ListaSeguimiento).where(ListaSeguimiento.usuario_id == u.id, ListaSeguimiento.empresa_id == e.id)):
        registrar(db, "WATCHLIST_AGREGAR", "ERROR", u, ip_cliente(request), f"{e.ticker} duplicado")
        raise HTTPException(status.HTTP_409_CONFLICT, f"{e.nombre_empresa} ya está en tu lista de seguimiento.")  # CA-4
    item = ListaSeguimiento(usuario_id=u.id, empresa_id=e.id)
    db.add(item)
    db.commit()
    registrar(db, "WATCHLIST_AGREGAR", "EXITO", u, ip_cliente(request), e.ticker)
    return _watch_out(item, e)


@router.patch("/watchlist/{ticker}", response_model=WatchOut)
def actualizar(ticker: str, datos: WatchPatch, request: Request, u: Usuario = Depends(usuario_actual),
               db: Session = Depends(get_db)):
    item, e = _item(db, u, ticker)
    if datos.cantidad is not None:
        item.cantidad = datos.cantidad
    if datos.favorito is not None:
        item.favorito = datos.favorito
    db.commit()
    registrar(db, "PORTAFOLIO_ACTUALIZAR", "EXITO", u, ip_cliente(request), f"{e.ticker} {datos.model_dump(exclude_none=True)}")
    return _watch_out(item, e)


@router.delete("/watchlist/{ticker}", status_code=204)
def quitar(ticker: str, request: Request, u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    item, e = _item(db, u, ticker)
    db.delete(item)
    db.commit()
    registrar(db, "WATCHLIST_ELIMINAR", "EXITO", u, ip_cliente(request), e.ticker)
    return Response(status_code=204)


# ---------------------------------------------------------------- simulaciones (HU-05)
@router.post("/simulaciones", response_model=SimulacionOut, status_code=201)
def simular(datos: SimulacionIn, request: Request, u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    e = _empresa(db, datos.ticker)
    fechas, pa, pi = servicios.alineadas(db, e)
    sel = [i for i, f in enumerate(fechas) if datos.fecha_inicio <= f <= datos.fecha_fin]
    try:
        if datos.fecha_fin <= datos.fecha_inicio:
            raise ValueError("La fecha final debe ser posterior a la inicial")
        r = calculos.simular(datos.monto, [pa[i] for i in sel], [pi[i] for i in sel], [fechas[i] for i in sel])
    except ValueError as ex:  # CA-4
        registrar(db, "SIMULACION", "ERROR", u, ip_cliente(request), str(ex))
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(ex))
    s = SimulacionPortafolio(usuario_id=u.id, empresa_id=e.id, monto_inicial=datos.monto, fecha_inicio=fechas[sel[0]],
                             fecha_fin=fechas[sel[-1]], valor_final=r["valor_final"])
    db.add(s)
    db.commit()
    registrar(db, "SIMULACION", "EXITO", u, ip_cliente(request), f"{e.ticker} {datos.monto} → {r['valor_final']}")
    return SimulacionOut(id=s.id, ticker=e.ticker, monto=datos.monto, fecha_inicio=s.fecha_inicio, fecha_fin=s.fecha_fin,
                         **{k: r[k] for k in ("valor_final", "valor_indice", "retorno", "retorno_indice", "serie")})


@router.get("/simulaciones", response_model=list[SimulacionHist])
def historial(u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    filas = db.execute(select(SimulacionPortafolio, Empresa).join(Empresa, Empresa.id == SimulacionPortafolio.empresa_id)
                       .where(SimulacionPortafolio.usuario_id == u.id)
                       .order_by(SimulacionPortafolio.id.desc()).limit(20)).all()
    return [SimulacionHist(id=s.id, ticker=e.ticker, monto=float(s.monto_inicial), fecha_inicio=s.fecha_inicio,
                           fecha_fin=s.fecha_fin, valor_final=float(s.valor_final), fecha_ejecucion=s.fecha_ejecucion)
            for s, e in filas]


# ---------------------------------------------------------------- alertas (HU-06)
def _alerta_out(r: ReglaAlerta, e: Empresa) -> AlertaOut:
    return AlertaOut(id=r.id, ticker=e.ticker, nombre=e.nombre_empresa, tipo=r.tipo_condicion, umbral=float(r.umbral),
                     canal=r.canal, estado=r.estado, pendiente_envio=r.pendiente_envio, fecha_creacion=r.fecha_creacion)


def _regla(db: Session, u: Usuario, id_: int) -> ReglaAlerta:
    r = db.get(ReglaAlerta, id_)
    if r is None or r.usuario_id != u.id:  # la regla de otro usuario "no existe" para este
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Alerta no encontrada")
    return r


@router.get("/alertas", response_model=list[AlertaOut])
def alertas(u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    filas = db.execute(select(ReglaAlerta, Empresa).join(Empresa, Empresa.id == ReglaAlerta.empresa_id)
                       .where(ReglaAlerta.usuario_id == u.id).order_by(ReglaAlerta.id.desc())).all()
    return [_alerta_out(r, e) for r, e in filas]


@router.post("/alertas", response_model=AlertaOut, status_code=201)
def crear_alerta(datos: AlertaIn, request: Request, u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    e = _empresa(db, datos.ticker)
    r = ReglaAlerta(usuario_id=u.id, empresa_id=e.id, tipo_condicion=datos.tipo, umbral=datos.umbral, canal=datos.canal)
    db.add(r)
    db.commit()
    registrar(db, "ALERTA_CREAR", "EXITO", u, ip_cliente(request), f"{e.ticker} {datos.tipo} {datos.umbral}")
    return _alerta_out(r, e)


@router.patch("/alertas/{id_}", response_model=AlertaOut)
def cambiar_alerta(id_: int, datos: AlertaPatch, request: Request, u: Usuario = Depends(usuario_actual),
                   db: Session = Depends(get_db)):
    r = _regla(db, u, id_)
    r.estado, r.pendiente_envio = datos.estado, False
    db.commit()
    registrar(db, "ALERTA_ESTADO", "EXITO", u, ip_cliente(request), f"{id_} → {datos.estado}")
    return _alerta_out(r, db.get(Empresa, r.empresa_id))


@router.delete("/alertas/{id_}", status_code=204)
def borrar_alerta(id_: int, request: Request, u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    r = _regla(db, u, id_)
    db.delete(r)
    db.commit()
    registrar(db, "ALERTA_ELIMINAR", "EXITO", u, ip_cliente(request), str(id_))
    return Response(status_code=204)


@router.post("/alertas/evaluar")
def evaluar(u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    """Ejecuta ahora la misma revisión que hace el worker de Celery, solo para las reglas del usuario."""
    return servicios.evaluar_alertas(db, u.id)


@router.get("/notificaciones", response_model=list[NotificacionOut])
def notificaciones(u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    return db.scalars(select(Notificacion).where(Notificacion.usuario_id == u.id)
                      .order_by(Notificacion.id.desc()).limit(20)).all()


# ---------------------------------------------------------------- auditoría (RNF-04)
@router.get("/auditoria", response_model=list[AuditoriaOut])
def auditoria(u: Usuario = Depends(usuario_actual), db: Session = Depends(get_db)):
    return db.scalars(select(Auditoria).where(Auditoria.usuario_id == u.id).order_by(Auditoria.id.desc()).limit(100)).all()
