"""Lógica de aplicación compartida por la API y por los workers de Celery."""
import logging
import time as reloj
from datetime import date, datetime, time, timezone

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import cache, calculos
from .config import settings
from .db import engine
from .models import Empresa, ListaSeguimiento, MetricaEmpresa, PrecioHistorico, Usuario
from .proveedores import ErrorProveedor, ProveedorSimulado, con_resiliencia, obtener_proveedor

log = logging.getLogger("asimetria")
INDICE = "^NDX"


def _insert(modelo):
    if engine.dialect.name == "postgresql":
        from sqlalchemy.dialects.postgresql import insert
    else:
        from sqlalchemy.dialects.sqlite import insert
    return insert(modelo)


def _ts(f: date) -> datetime:
    return datetime.combine(f, time(21, 0), tzinfo=timezone.utc)  # cierre NYSE/NASDAQ ≈ 21:00 UTC


def empresa(db: Session, ticker: str) -> Empresa | None:
    return db.scalar(select(Empresa).where(Empresa.ticker == ticker, Empresa.activa))


def guardar_barras(db: Session, empresa_id: int, barras, fuente: str) -> int:
    """Inserta precios sin duplicar: ON CONFLICT (time, empresa_id) DO NOTHING (HU-02 CA-3).
    Cada fila guarda su procedencia (`fuente`) y cuándo se almacenó (`fecha_ingesta`)."""
    if not barras:
        return 0
    ahora = datetime.now(timezone.utc)
    filas = [{"time": _ts(b.fecha), "empresa_id": empresa_id, "precio_apertura": b.apertura,
              "precio_maximo": b.maximo, "precio_minimo": b.minimo, "precio_cierre": b.cierre,
              "volumen": b.volumen, "fuente": fuente, "fecha_ingesta": ahora} for b in barras]
    res = db.execute(_insert(PrecioHistorico).values(filas).on_conflict_do_nothing(index_elements=["time", "empresa_id"]))
    db.commit()
    return res.rowcount or 0


# ---------------------------------------------------------------- carga inicial (HU-02 CA-1)
def carga_inicial(db: Session, proveedor=None) -> bool:
    """Carga catálogo y 5 años de historia si la BD está vacía. Idempotente."""
    if db.scalar(select(func.count()).select_from(Empresa)):
        return False
    prov = proveedor or obtener_proveedor()
    dividendos = {}
    for ticker, nombre, sector in prov.catalogo():
        e = Empresa(ticker=ticker, nombre_empresa=nombre, sector=sector, es_indice=ticker == INDICE)
        db.add(e)
        db.flush()
        for lote in _lotes(prov.historico(ticker), 500):
            guardar_barras(db, e.id, lote, prov.nombre)
        dividendos[ticker] = prov.dividendos(ticker)
    recalcular_metricas(db, dividendos)
    if settings.modo_demo:
        crear_usuario_demo(db)
    log.info("Carga inicial completada")
    return True


def _lotes(xs, n):
    for i in range(0, len(xs), n):
        yield xs[i:i + n]


def crear_usuario_demo(db: Session) -> Usuario:
    from .security import hash_password
    import secrets

    u = db.scalar(select(Usuario).where(Usuario.email == "demo@asimetria.app"))
    if u:
        return u
    u = Usuario(email="demo@asimetria.app", nombre="Oscar Hernández", perfil_riesgo="MODERADO",
                password_hash=hash_password(secrets.token_urlsafe(16)))  # contraseña aleatoria: solo vía /auth/demo
    db.add(u)
    db.flush()
    for t, q, fav in [("RTC", 120, False), ("NVC", 80, True), ("QFN", 150, True), ("GLX", 55, False)]:
        e = empresa(db, t)
        if e:
            db.add(ListaSeguimiento(usuario_id=u.id, empresa_id=e.id, cantidad=q, favorito=fav))
    db.commit()
    return u


# ---------------------------------------------------------------- series
def cierres(db: Session, empresa_id: int, dias: int | None = None) -> tuple[list[date], list[float]]:
    q = select(PrecioHistorico.time, PrecioHistorico.precio_cierre).where(PrecioHistorico.empresa_id == empresa_id)
    if dias:
        filas = db.execute(q.order_by(PrecioHistorico.time.desc()).limit(dias + 1)).all()[::-1]
    else:
        filas = db.execute(q.order_by(PrecioHistorico.time)).all()
    return [f[0].date() for f in filas], [float(f[1]) for f in filas]


def alineadas(db: Session, e: Empresa) -> tuple[list[date], list[float], list[float]]:
    """Cierres del activo y del índice en las mismas fechas."""
    fi, ci = cierres(db, empresa(db, INDICE).id)
    idx = dict(zip(fi, ci))
    fa, ca = cierres(db, e.id)
    pares = [(f, a, idx[f]) for f, a in zip(fa, ca) if f in idx]
    return [p[0] for p in pares], [p[1] for p in pares], [p[2] for p in pares]


# ---------------------------------------------------------------- métricas (HU-03, HU-10)
def recalcular_metricas(db: Session, dividendos: dict | None = None) -> int:
    """Recalcula betas, volatilidad, correlación y retorno esperado (CAPM) de cada empresa."""
    _, ci = cierres(db, empresa(db, INDICE).id)
    rm_hist = calculos.retornos(ci)
    n = 0
    for e in db.scalars(select(Empresa).where(Empresa.activa, Empresa.es_indice.is_(False))):
        _, pa, pi = alineadas(db, e)
        ra, rm = calculos.retornos(pa), calculos.retornos(pi)
        m = db.scalar(select(MetricaEmpresa).where(MetricaEmpresa.empresa_id == e.id)) or MetricaEmpresa(empresa_id=e.id)
        try:
            b = calculos.beta_asimetrica(ra, rm)
            m.beta_alcista, m.beta_bajista, m.beta = b["alcista"], b["bajista"], b["global"]
            m.correlacion = calculos.correlacion(ra, rm)
            m.retorno_esperado = calculos.retorno_esperado_capm(b["global"], rm_hist)
        except calculos.DatosInsuficientes:
            m.beta_alcista = m.beta_bajista = m.beta = m.correlacion = m.retorno_esperado = None
        m.volatilidad = calculos.volatilidad(ra)
        if dividendos and e.ticker in dividendos:
            m.rendimiento_dividendos = dividendos[e.ticker]
        m.fecha_actualizacion = datetime.now(timezone.utc)
        db.add(m)
        n += 1
    db.commit()
    for pref in ("metrics:", "analisis:", "reco:", "destacado"):
        cache.invalidar(pref)  # el próximo GET recomputa con datos nuevos
    return n


def _f(x):
    return None if x is None else float(x)


def metricas_listado(db: Session) -> list[dict]:
    """GET /api/v1/metrics/ — cacheado en Redis (RNF-01)."""
    datos = cache.get_json("metrics:all")
    if datos is not None:
        return datos
    filas = db.execute(select(Empresa, MetricaEmpresa).join(MetricaEmpresa, MetricaEmpresa.empresa_id == Empresa.id)
                       .where(Empresa.activa).order_by(Empresa.id)).all()
    datos = []
    for e, m in filas:
        _, c = cierres(db, e.id, dias=20)
        vol = float(m.volatilidad)
        datos.append({
            "ticker": e.ticker, "nombre": e.nombre_empresa, "sector": e.sector,
            "beta_alcista": _f(m.beta_alcista), "beta_bajista": _f(m.beta_bajista), "beta": _f(m.beta),
            "ratio_asimetria": float(m.beta_alcista / m.beta_bajista) if m.beta_alcista and m.beta_bajista else None,
            "correlacion": _f(m.correlacion), "volatilidad": vol, "nivel_volatilidad": calculos.nivel_volatilidad(vol),
            "retorno_esperado": _f(m.retorno_esperado), "dividendos": _f(m.rendimiento_dividendos),
            "error": None if m.beta_alcista is not None else calculos.ERR_DATOS,
            "tendencia": c, "fecha_actualizacion": m.fecha_actualizacion.isoformat(),
        })
    cache.set_json("metrics:all", datos, settings.cache_ttl)
    return datos


def analisis(db: Session, e: Empresa, ventana: int = 90) -> dict:
    """Vista NASDAQ 100 vs activo (HU-03/HU-09): base 100, retornos, drawdown, dispersión y desglose."""
    clave = f"analisis:{e.ticker}:{ventana}"
    datos = cache.get_json(clave)
    if datos is not None:
        return datos
    fechas, pa, pi = alineadas(db, e)
    n = min(ventana, len(pa) - 1)
    fechas, pa, pi = fechas[-n - 1:], pa[-n - 1:], pi[-n - 1:]
    ra, ri = calculos.retornos(pa) * 100, calculos.retornos(pi) * 100
    ret, ret_i = (pa[-1] / pa[0] - 1) * 100, (pi[-1] / pi[0] - 1) * 100

    def dia(objetivo):  # sesión cuyo movimiento del índice es el más cercano a ±2 % (caso del documento)
        i = min(range(len(ri)), key=lambda k: abs(ri[k] - objetivo))
        return {"fecha": fechas[i + 1].isoformat(), "indice": float(ri[i]), "activo": float(ra[i]),
                "ratio": abs(float(ra[i] / ri[i])) if ri[i] else None}

    datos = {
        "ticker": e.ticker, "nombre": e.nombre_empresa, "ventana": n,
        "fechas": [f.isoformat() for f in fechas],
        "base100_activo": [p / pa[0] * 100 for p in pa], "base100_indice": [p / pi[0] * 100 for p in pi],
        "retorno": ret, "retorno_indice": ret_i, "alfa": ret - ret_i, "max_drawdown": calculos.max_drawdown(pa),
        "dispersion": [[float(x), float(y)] for x, y in zip(ri, ra)],
        "desglose": {"alcista": dia(2.0), "bajista": dia(-2.0),
                     "promedio": {"indice": float(ri.mean()), "activo": float(ra.mean()),
                                  "ratio": float(ra.mean() / ri.mean()) if ri.mean() else None}},
    }
    cache.set_json(clave, datos, settings.cache_ttl)
    return datos


# ---------------------------------------------------------------- cotizaciones en vivo (HU-01, HU-09, RNF-06)
def _ultimos_cierres(db: Session) -> dict[str, tuple[float, float, date]]:
    """ticker → (último cierre, cierre anterior, fecha último) desde la BD (fallback local)."""
    out = {}
    for e in db.scalars(select(Empresa).where(Empresa.activa)):
        f, c = cierres(db, e.id, dias=1)
        if c:
            out[e.ticker] = (c[-1], c[-2] if len(c) > 1 else c[-1], f[-1])
    return out


def cotizaciones_actuales(db: Session) -> dict:
    """Precio en vivo desde la caché; si no existe, último cierre almacenado (nunca error 500)."""
    base = _ultimos_cierres(db)
    hoy = datetime.now(timezone.utc).date()
    estado = cache.get_json("cotizaciones:estado") or {"desactualizado": True, "motivo": "sin datos en vivo aún",
                                                      "ultima_actualizacion": None}
    cot = {}
    for t, (ultimo, anterior, f_ultimo) in base.items():
        vivo = cache.get_json(f"cotizacion:{t}")
        if vivo:  # variación intradía frente al último cierre previo a hoy
            precio, ref = vivo["precio"], (anterior if f_ultimo == hoy else ultimo)
        else:  # fallback: último cierre almacenado y su variación diaria
            precio, ref = ultimo, anterior
        cot[t] = {"precio": precio, "cierre_anterior": ref, "variacion": (precio / ref - 1) * 100 if ref else 0.0}
    return {"estado": estado, "cotizaciones": cot}


def sincronizar_cotizaciones(db: Session, proveedor=None, dormir=reloj.sleep) -> dict:
    """Tarea periódica: pide precios al proveedor con reintentos/circuit breaker y los publica en caché."""
    prov = proveedor or obtener_proveedor()
    ultimos = {}
    for t, (ultimo, _, _) in _ultimos_cierres(db).items():
        vivo = cache.get_json(f"cotizacion:{t}")
        ultimos[t] = vivo["precio"] if vivo else ultimo
    ahora = datetime.now(timezone.utc).isoformat()
    try:
        precios = con_resiliencia(prov.cotizaciones, ultimos, dormir=dormir)
    except ErrorProveedor as e:
        previo = cache.get_json("cotizaciones:estado") or {}
        estado = {"desactualizado": True, "motivo": str(e), "ultima_actualizacion": previo.get("ultima_actualizacion")}
        cache.set_json("cotizaciones:estado", estado)
        return estado
    for t, p in precios.items():
        cache.set_json(f"cotizacion:{t}", {"precio": p, "ts": ahora}, ttl=86400)
    estado = {"desactualizado": False, "motivo": None, "ultima_actualizacion": ahora}
    cache.set_json("cotizaciones:estado", estado)
    return estado


def cierre_diario(db: Session, proveedor=None, hoy: date | None = None) -> int:
    """Tarea diaria (HU-02 CA-2): guarda el cierre del día sin duplicar y recalcula métricas."""
    prov = proveedor or obtener_proveedor()
    hoy = hoy or datetime.now(timezone.utc).date()
    if hoy.weekday() >= 5:
        return 0
    n = 0
    for e in db.scalars(select(Empresa).where(Empresa.activa)).all():
        if isinstance(prov, ProveedorSimulado):
            vivo = cache.get_json(f"cotizacion:{e.ticker}")
            if not vivo:
                continue
            from .proveedores import Barra
            p = vivo["precio"]
            barras, fuente = [Barra(hoy, p, p, p, p, 0)], "COTIZACION_VIVO"
        else:
            try:
                barras, fuente = con_resiliencia(prov.historico, e.ticker, 5), prov.nombre
            except ErrorProveedor:
                continue  # los datos ya guardados no se dañan (HU-02 CA-4); se reintenta mañana
        n += guardar_barras(db, e.id, barras, fuente)
    recalcular_metricas(db)
    return n


# ---------------------------------------------------------------- alertas (HU-06)
class ErrorNotificacion(Exception):
    pass


def enviar_sendgrid(destino: str, asunto: str, texto: str, http=None, sandbox: bool = False) -> str | None:
    """POST /v3/mail/send trazado en `llamadas_externas`. Devuelve el X-Message-Id para rastrear la entrega.
    Con sandbox=True SendGrid valida la petición y la clave pero NO entrega el correo."""
    import time as t

    import httpx

    from .auditoria import registrar_llamada

    cuerpo = {"personalizations": [{"to": [{"email": destino}]}], "from": {"email": settings.correo_remitente},
              "subject": asunto, "content": [{"type": "text/plain", "value": texto}]}
    if sandbox:
        cuerpo["mail_settings"] = {"sandbox_mode": {"enable": True}}
    operacion = "mail.send (sandbox)" if sandbox else "mail.send"
    t0 = t.perf_counter()
    try:
        r = (http or httpx.Client(timeout=10)).post("https://api.sendgrid.com/v3/mail/send", json=cuerpo,
                                                    headers={"Authorization": f"Bearer {settings.sendgrid_key}"})
    except httpx.HTTPError as e:
        registrar_llamada("SENDGRID", operacion, False, None, int((t.perf_counter() - t0) * 1000), str(e)[:200])
        raise ErrorNotificacion("No fue posible conectar con SendGrid")
    mid = r.headers.get("x-message-id")
    registrar_llamada("SENDGRID", operacion, r.status_code < 300, r.status_code, int((t.perf_counter() - t0) * 1000),
                      f"message-id {mid}" if mid else r.text[:200])
    if r.status_code >= 300:
        raise ErrorNotificacion(f"SendGrid respondió {r.status_code}")
    return mid


def enviar_notificacion(usuario: Usuario, texto: str, canal: str, http=None) -> str | None:
    """Correo vía SendGrid si hay API key (devuelve su message-id); si no, queda solo en la app (None)."""
    if cache.get_json("sim:correo_caido"):
        raise ErrorNotificacion("Servicio de correo no disponible")
    if canal == "CORREO" and settings.sendgrid_key:
        return enviar_sendgrid(usuario.email, "Alerta de Asimetría", texto, http=http)
    return None


def evaluar_alertas(db: Session, usuario_id: int | None = None) -> dict:
    """Worker: revisa reglas ACTIVAS y envíos pendientes. Si el correo falla, la alerta queda pendiente y se
    reintenta en la siguiente pasada: nunca se pierde (CA-4)."""
    from .auditoria import registrar
    from .models import Notificacion, ReglaAlerta

    cot = cotizaciones_actuales(db)["cotizaciones"]
    q = select(ReglaAlerta, Empresa, Usuario).join(Empresa, Empresa.id == ReglaAlerta.empresa_id) \
        .join(Usuario, Usuario.id == ReglaAlerta.usuario_id) \
        .where((ReglaAlerta.estado == "ACTIVA") | ReglaAlerta.pendiente_envio)
    if usuario_id:
        q = q.where(ReglaAlerta.usuario_id == usuario_id)
    res = {"disparadas": 0, "pendientes": 0}
    for regla, e, u in db.execute(q).all():
        c = cot.get(e.ticker)
        if c is None:
            continue
        if not regla.pendiente_envio and not calculos.evaluar_alerta(regla.tipo_condicion, float(regla.umbral),
                                                                    c["precio"], c["variacion"]):
            continue  # CA-3: condición no cumplida → no se notifica
        texto = (f"{e.nombre_empresa} alcanzó ${c['precio']:,.2f}" if regla.tipo_condicion == "PRECIO_LIMITE"
                 else f"{e.nombre_empresa} varió {c['variacion']:+.2f}% (umbral {float(regla.umbral):g}%)")
        regla.estado = "DISPARADA"
        try:
            mid = enviar_notificacion(u, texto, regla.canal)
            db.add(Notificacion(usuario_id=u.id, regla_id=regla.id, texto=texto, canal=regla.canal,
                                estado_envio="ENVIADO_CORREO" if mid else "EN_APP", proveedor_id=mid))
            regla.pendiente_envio = False
            res["disparadas"] += 1
            registrar(db, "ALERTA_DISPARADA", "EXITO", u, detalle=texto)
        except Exception as ex:  # noqa: BLE001
            regla.pendiente_envio = True
            res["pendientes"] += 1
            registrar(db, "ALERTA_ENVIO", "ERROR", u, detalle=str(ex))
        db.commit()
    return res
