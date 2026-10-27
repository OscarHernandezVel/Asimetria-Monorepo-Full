"""Caché compartida (RNF-01). Redis en producción; diccionario en memoria si no hay REDIS_URL.
Si Redis falla, las operaciones degradan a "sin caché" en vez de romper la petición (RNF-06)."""
import json
import logging
import threading
import time
from typing import Any

from .config import settings

log = logging.getLogger("asimetria.cache")


class CacheMemoria:
    def __init__(self) -> None:
        self._d: dict[str, tuple[str, float | None]] = {}
        self._lock = threading.Lock()  # ponytail: un solo lock global; basta para un proceso de desarrollo

    def get(self, k: str) -> str | None:
        with self._lock:
            v = self._d.get(k)
            if v and v[1] is not None and v[1] < time.time():
                self._d.pop(k, None)
                return None
            return v[0] if v else None

    def set(self, k: str, v: str, ex: int | None = None) -> None:
        with self._lock:
            self._d[k] = (v, time.time() + ex if ex else None)

    def delete(self, *ks: str) -> None:
        with self._lock:
            for k in ks:
                self._d.pop(k, None)

    def incr(self, k: str) -> int:
        with self._lock:
            v, exp = self._d.get(k, ("0", None))
            n = int(v) + 1
            self._d[k] = (str(n), exp)
            return n

    def expire(self, k: str, s: int) -> None:
        with self._lock:
            if k in self._d:
                self._d[k] = (self._d[k][0], time.time() + s)

    def scan_iter(self, match: str):
        pref = match.rstrip("*")
        with self._lock:
            return [k for k in self._d if k.startswith(pref)]

    def ping(self) -> bool:
        return True


def _crear():
    if settings.redis_url:
        import redis

        return redis.Redis.from_url(settings.redis_url, decode_responses=True, socket_timeout=0.5, socket_connect_timeout=0.5)
    return CacheMemoria()


cliente = _crear()
TIPO = "redis" if settings.redis_url else "memoria"

# Circuit breaker de la propia caché: tras un fallo, se omite Redis 10 s y todo va directo a la BD.
# Sin esto, con Redis caído cada operación esperaba su timeout y una petición acumulaba >15 s (medido).
_caida_hasta = 0.0


def _disponible() -> bool:
    return time.time() >= _caida_hasta


def _fallo(op: str, e: Exception) -> None:
    global _caida_hasta
    _caida_hasta = time.time() + 10
    log.warning("cache %s falló, se omite Redis 10 s: %s", op, e)


def get_json(k: str) -> Any:
    if not _disponible():
        return None
    try:
        v = cliente.get(k)
        return json.loads(v) if v else None
    except Exception as e:  # Redis caído → se recalcula desde la BD
        _fallo("get", e)
        return None


def get_raw(k: str) -> str | None:
    """JSON ya serializado, para responder sin deserializar ni revalidar (ruta caliente de RNF-01)."""
    if not _disponible():
        return None
    try:
        return cliente.get(k)
    except Exception as e:
        _fallo("get", e)
        return None


def set_json(k: str, v: Any, ttl: int | None = None) -> None:
    if not _disponible():
        return
    try:
        cliente.set(k, json.dumps(v, default=str, ensure_ascii=False, separators=(",", ":")), ex=ttl)  # compacto, igual que FastAPI
    except Exception as e:
        _fallo("set", e)


def invalidar(prefijo: str) -> None:
    if not _disponible():
        return
    try:
        ks = list(cliente.scan_iter(match=prefijo + "*"))
        if ks:
            cliente.delete(*ks)
    except Exception as e:
        _fallo("invalidar", e)


def contador(k: str, ttl: int) -> int:
    """Incrementa un contador con expiración (bloqueo de intentos de login)."""
    if not _disponible():
        return 0
    try:
        n = cliente.incr(k)
        if n == 1:
            cliente.expire(k, ttl)
        return n
    except Exception as e:  # sin Redis no hay bloqueo de intentos (fail-open, documentado)
        _fallo("contador", e)
        return 0


def ping() -> bool:
    try:
        return bool(cliente.ping())
    except Exception:
        return False
