# Despliegue en producción

Este documento explica cómo publicar Asimetría con **dominio propio y HTTPS**, desplegando automáticamente desde GitHub.

## 1. Arquitectura y por qué

```
                 GitHub (código · Actions · GHCR)
                   │ push a main → CI (pruebas, cobertura ≥ 80 %) → Deploy (imágenes) → SSH
                   ▼
  Internet ─HTTPS/TLS 1.3, HTTP/3─▶ Caddy ─▶ Nginx (web + límites por IP) ─▶ FastAPI ×N procesos
            (certificados automáticos)                                         │      │
                                                          PostgreSQL+TimescaleDB   Redis ◀─ Celery worker/beat
                                                          + backup diario                    │
                                                                                Alpha Vantage · SendGrid
```

| Criterio | Cómo se cumple |
|---|---|
| **Dominio seguro** | Caddy emite y renueva solo los certificados Let's Encrypt; **solo TLS 1.3** (RNF-03), HSTS y redirección HTTP→HTTPS. Nginx añade CSP, `X-Frame-Options` y límites por IP (15 req/s a la API, 10 req/min a la autenticación). Cortafuegos con solo 22/80/443, SSH sin contraseñas, actualizaciones de seguridad automáticas. Solo Caddy queda expuesto: la BD, Redis y la API no tienen puertos públicos. |
| **Alto rendimiento** | HTTP/3 y compresión zstd/gzip en el borde; respuestas de Redis servidas sin re-serializar; P95 medido de 438–646 ms con 100 usuarios concurrentes (ver `ISO-25010.md` §1). Las imágenes vienen precompiladas desde GHCR (el servidor no compila). |
| **Escalabilidad** | La API no tiene estado (JWT + Redis compartido), así que escala en vertical (más CPU → más `UVICORN_WORKERS`) y en horizontal (más contenedores o más servidores). Ruta de crecimiento en §9. |
| **Mantenible a corto plazo** | Un solo servidor, el mismo `docker-compose.yml` de desarrollo más un *override* de producción, despliegue automático en cada push y *rollback* con un comando. |

**¿Por qué no solo GitHub Pages?** Pages únicamente sirve archivos estáticos: no puede ejecutar la API, la base de datos ni los workers. GitHub aloja el **código**, la **integración continua** y las **imágenes** (GHCR); el sistema **corre** en el servidor.

**Costo estimado:** dominio US$10–15 al año + VPS de 2 vCPU y 4 GB de RAM (Hetzner CX22 ~€4,5/mes, DigitalOcean o Vultr ~US$12/mes, o Azure con el crédito de estudiante de GitHub Student Pack). Todo lo demás es gratuito (Let's Encrypt, GitHub Actions y GHCR para repos públicos).

## 2. Comprar el dominio

En Cloudflare Registrar (a precio de costo), Namecheap o Porkbun. Para estudiantes, el **GitHub Student Developer Pack** incluye dominios gratis por un año (`.me` en Namecheap, `.tech`, entre otros). La compra la haces tú; nadie más debe introducir tus datos de pago.

## 3. Crear el servidor

- Imagen: **Ubuntu 24.04 LTS**, 2 vCPU y 4 GB de RAM como mínimo.
- Al crearlo, añade tu **clave SSH pública** (no uses contraseña). Si no tienes una, créala en tu PC con `ssh-keygen -t ed25519`.
- Activa los **backups o snapshots** del proveedor: es la copia fuera del servidor que complementa el backup diario.

## 4. DNS

En el panel del dominio crea dos registros:

| Tipo | Nombre | Valor |
|---|---|---|
| A | `@` (o `asimetria`) | IP pública del servidor |
| A | `www` | IP pública del servidor |

En Cloudflare deja la nube en **gris** (*DNS only*) para que Caddy obtenga el certificado directamente. Comprueba la propagación con `nslookup tudominio.com`.

## 5. Preparar el servidor (una vez)

Conéctate como root con `ssh root@IP` y ejecuta el script (revísalo antes):

```bash
curl -fsSL https://raw.githubusercontent.com/OscarHernandezVel/Asimetria-Monorepo/main/deploy/preparar-servidor.sh -o p.sh && less p.sh && sh p.sh
```

Instala Docker, crea el usuario `deploy`, desactiva el login con contraseña y el de root, activa el cortafuegos y `fail2ban`, y clona el repo en `/opt/asimetria`. Desde aquí entra como `ssh deploy@IP`.

Crea el archivo de secretos **en el servidor** (nunca en GitHub):

```bash
cd /opt/asimetria && cp .env.example .env && chmod 600 .env && nano .env
```

Valores obligatorios en producción:

- `POSTGRES_PASSWORD` y `JWT_SECRET`: nuevos y largos. Genéralos con `openssl rand -base64 48`; **no reutilices los de tu PC**.
- `DOMINIO=tudominio.com` y `ACME_EMAIL=tu-correo`.
- `HERRAMIENTAS_PRUEBA=0`. `MODO_DEMO=1` solo si quieres el botón de demo pública.
- `BCRYPT_ROUNDS=12` y `UVICORN_WORKERS=2` para un servidor de 2 vCPU.
- `SENDGRID_API_KEY`, `CORREO_REMITENTE` y `ALPHAVANTAGE_API_KEY`: los mismos de tu `.env` local o, mejor, claves nuevas exclusivas para producción.

## 6. Conectar GitHub Actions con el servidor

**1. Clave exclusiva para el despliegue.** En tu PC:

```bash
ssh-keygen -t ed25519 -f asimetria_deploy -N "" -C "github-actions"
```

Copia el contenido de `asimetria_deploy.pub` al final de `/home/deploy/.ssh/authorized_keys` del servidor.

**2. Configuración en GitHub.** En el repo, ve a *Settings → Environments → New environment* y crea uno llamado `produccion`. Puedes exigir aprobación manual antes de cada despliegue. Dentro del entorno añade:

| Tipo | Nombre | Valor |
|---|---|---|
| Secret | `DEPLOY_SSH_KEY` | Contenido de `asimetria_deploy` (la clave **privada**) |
| Secret | `DEPLOY_KNOWN_HOSTS` | Salida de `ssh-keyscan IP_DEL_SERVIDOR` |
| Variable | `DEPLOY_HOST` | IP o dominio del servidor |
| Variable | `DEPLOY_USER` | `deploy` |
| Variable | `DOMINIO` | `tudominio.com` |

Después borra `asimetria_deploy` de tu PC o guárdalo en un gestor de contraseñas.

**3. Imágenes en GHCR.** El primer despliegue publica `asimetria-api` y `asimetria-web` en *Packages*. Si el servidor no puede descargarlas (`denied`), tienes dos opciones:
- En cada *package*, ve a *Package settings → Change visibility → Public*.
- O, en el servidor, inicia sesión con un token de solo lectura: `echo TOKEN | docker login ghcr.io -u OscarHernandezVel --password-stdin` (token *classic* con permiso `read:packages`).

## 7. Primer despliegue y verificación

Haz push a `main`, o lanza el despliegue a mano desde *Actions → Deploy → Run workflow*. El flujo es: **CI** (68+ pruebas) → **Deploy** (imágenes → SSH → `deploy/desplegar.sh` → comprobación de `https://DOMINIO/health`).

El primer arranque crea el esquema y carga los datos; tarda ~1–2 min. Luego comprueba:

- [ ] `https://tudominio.com` carga, con candado y sin avisos.
- [ ] [SSL Labs](https://www.ssllabs.com/ssltest/) da **A** o superior, con TLS 1.3.
- [ ] [securityheaders.com](https://securityheaders.com) muestra CSP, HSTS y X-Frame-Options.
- [ ] `https://tudominio.com/health` devuelve `"estado":"ok"`.
- [ ] Un monitor externo (UptimeRobot o Better Stack, gratuitos) sondea `/health` cada 60 s y te avisa por correo (RNF-05).

## 8. Operación diaria

| Tarea | Comando (en `/opt/asimetria`, como `deploy`) |
|---|---|
| Alias útil | `alias dc='docker compose -f docker-compose.yml -f docker-compose.prod.yml'` |
| Estado | `dc ps` |
| Logs (JSON: auditoría y llamadas externas) | `dc logs -f api worker` |
| **Rollback** | `TAG=<sha anterior> ./deploy/desplegar.sh` (los sha están en *Actions* o en `git log`) |
| Ver backups | `ls -lh backups/` (uno diario, 7 días de retención) |
| **Restaurar** un backup | `dc stop api worker beat` → `dc exec -T db psql -U asimetria -d asimetria -c "SELECT timescaledb_pre_restore();"` → `dc exec -T db pg_restore -U asimetria -d asimetria --clean --if-exists < backups/asimetria-AAAA-MM-DD.dump` → `dc exec -T db psql -U asimetria -d asimetria -c "SELECT timescaledb_post_restore();"` → `dc up -d` |
| Rotar un secreto | Editar `.env` y ejecutar `dc up -d` (cambiar `JWT_SECRET` cierra todas las sesiones) |

Los logs rotan solos (10 MB × 5 archivos por servicio), así que el disco no se llena.

## 9. Ruta de escalabilidad

Cada paso se da solo cuando las métricas lo pidan: P95 de `/metrics/` mayor de 1 s, CPU sostenida por encima del 70 % o RAM por encima del 80 %.

1. **Vertical (minutos):** redimensiona el VPS (4 → 8 vCPU) y sube `UVICORN_WORKERS`.
2. **Más contenedores en el mismo servidor:** `dc up -d --scale api=3 --scale worker=2`. Nginx reparte entre réplicas porque resuelve `api` por DNS cada 10 s. ⚠️ Conexiones: réplicas × `UVICORN_WORKERS` × 20 ≤ 100 (`max_connections` de PostgreSQL). Al superar ese límite, añade PgBouncer o reduce el pool.
3. **Datos gestionados:** mueve PostgreSQL a **Timescale Cloud** y Redis a un servicio gestionado (Upstash o Redis Cloud). Solo cambian `DATABASE_URL` y `REDIS_URL`; el código no se toca.
4. **Varios servidores de API** detrás de un balanceador (el del proveedor o Cloudflare con proxy activado). Es posible porque la API no guarda estado en el servidor.
5. **CDN:** activar el proxy de Cloudflare (nube naranja) cachea los estáticos en el borde. Antes, configura en Caddy `trusted_proxies` con los rangos de Cloudflare para no perder la IP real en la auditoría.

## 10. Pendientes antes de abrir a usuarios reales

- Copia de los backups **fuera** del servidor: snapshots del proveedor o `rclone` a almacenamiento de objetos.
- Revisar y aceptar las condiciones de uso de Alpha Vantage y SendGrid para un uso público.
- Términos y política de privacidad reales: el formulario de registro los menciona.
- Pagos reales mediante pasarela (Stripe, Wompi o PayU). El formulario actual no cobra.
