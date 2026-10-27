"""Configuración por variables de entorno (12-factor). Ningún secreto vive en el código (HU-01 CA-1)."""
import logging
import os
import secrets

log = logging.getLogger("asimetria")


class Settings:
    def __init__(self) -> None:
        self.database_url = os.getenv("DATABASE_URL", "sqlite:///./asimetria.db")
        self.redis_url = os.getenv("REDIS_URL", "")  # vacío → caché en memoria (solo desarrollo)
        self.jwt_secret = os.getenv("JWT_SECRET") or secrets.token_urlsafe(32)
        self.jwt_minutos = int(os.getenv("JWT_MINUTOS", "120"))  # RNF-03
        self.bcrypt_rounds = int(os.getenv("BCRYPT_ROUNDS", "12"))  # RNF-03: costo mínimo 12
        self.proveedor = os.getenv("PROVEEDOR", "simulado")  # simulado | alphavantage
        self.alphavantage_key = os.getenv("ALPHAVANTAGE_API_KEY", "")
        self.av_limite_diario = int(os.getenv("AV_LIMITE_DIARIO", "25"))  # cuota del plan gratuito
        self.sendgrid_key = os.getenv("SENDGRID_API_KEY", "")
        self.correo_remitente = os.getenv("CORREO_REMITENTE", "alertas@asimetria.app")
        self.cache_ttl = int(os.getenv("CACHE_TTL", "300"))
        self.intervalo_cotizaciones = int(os.getenv("INTERVALO_COTIZACIONES", "5"))
        self.modo_demo = os.getenv("MODO_DEMO", "1") == "1"  # cuenta demo pública
        # Inyección de fallos y prueba de integraciones afectan a TODOS los usuarios: apagar en producción.
        self.herramientas_prueba = os.getenv("HERRAMIENTAS_PRUEBA", os.getenv("MODO_DEMO", "1")) == "1"
        self.tareas_en_api = os.getenv("TAREAS_EN_API", "0") == "1"  # sin Celery: tareas en el mismo proceso
        self.servir_web = os.getenv("SERVIR_WEB", "0") == "1"
        self.cors_origenes = [o for o in os.getenv("CORS_ORIGENES", "").split(",") if o]
        if not os.getenv("JWT_SECRET"):
            if not self.database_url.startswith("sqlite"):
                # Con varios procesos cada uno generaría su propio secreto y los tokens fallarían al azar.
                raise RuntimeError("JWT_SECRET es obligatorio fuera de desarrollo: defínelo en .env")
            log.warning("JWT_SECRET no definido: se generó uno temporal (solo desarrollo; los tokens se invalidan al reiniciar).")
        if len(self.jwt_secret) < 32:
            log.warning("JWT_SECRET tiene menos de 32 caracteres: usa uno más largo para HS256.")


settings = Settings()
