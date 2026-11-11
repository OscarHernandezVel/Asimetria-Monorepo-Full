variable "proyecto" {
  type    = string
  default = "asimetria"
}

variable "entorno" {
  type    = string
  default = "produccion"
}

variable "region" {
  description = "us-east-1: la región más económica y con menor latencia a Colombia (~60 ms)"
  type        = string
  default     = "us-east-1"
}

variable "dominio" {
  description = "Dominio público de la aplicación, p. ej. asimetria.midominio.com"
  type        = string
}

variable "zona_route53" {
  description = "Zona alojada pública de Route 53 que contiene el dominio, p. ej. midominio.com"
  type        = string
}

variable "github_repo" {
  description = "Repositorio autorizado a desplegar (propietario/nombre)"
  type        = string
}

variable "crear_oidc_github" {
  description = "false si la cuenta ya tiene el proveedor OIDC de GitHub (solo puede existir uno)"
  type        = bool
  default     = true
}

variable "correo_alarmas" {
  description = "Correo que recibe las alarmas (hay que confirmar la suscripción). Vacío = sin correo"
  type        = string
  default     = ""
}

variable "imagen_tag" {
  description = "Solo para la primera definición de tarea; luego cada despliegue de GitHub Actions fija el sha"
  type        = string
  default     = "inicial"
}

# --- Cómputo (Fiabilidad · Eficiencia) ---
variable "api_cpu" {
  type    = number
  default = 512
}

variable "api_memoria" {
  type    = number
  default = 1024
}

variable "api_min" {
  description = "Mínimo de tareas: 2 en zonas distintas para tolerar la caída de una zona (RNF-05)"
  type        = number
  default     = 2
}

variable "api_max" {
  type    = number
  default = 6
}

# --- Datos ---
variable "bd_clase" {
  type    = string
  default = "db.t4g.micro"
}

variable "bd_multi_az" {
  description = "Réplica en espera en otra zona (failover automático ~60 s). Duplica el costo de RDS"
  type        = bool
  default     = false
}

variable "bd_almacenamiento_gb" {
  type    = number
  default = 20
}

variable "cache_nodo" {
  type    = string
  default = "cache.t4g.micro"
}

variable "cache_alta_disponibilidad" {
  description = "Réplica de Valkey con failover. Sin ella, si la caché cae la API sigue con PostgreSQL (RNF-06)"
  type        = bool
  default     = false
}

variable "nat_por_az" {
  description = "Un NAT por zona (alta disponibilidad de la salida a Alpha Vantage/SendGrid) o uno compartido"
  type        = bool
  default     = false
}

# --- Aplicación ---
variable "proveedor" {
  description = "simulado | alphavantage"
  type        = string
  default     = "simulado"
  validation {
    condition     = contains(["simulado", "alphavantage"], var.proveedor)
    error_message = "proveedor debe ser simulado o alphavantage."
  }
}

variable "modo_demo" {
  type    = bool
  default = true
}

variable "correo_remitente" {
  type    = string
  default = "alertas@asimetria.app"
}
