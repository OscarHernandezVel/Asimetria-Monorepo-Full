# PostgreSQL 16 gestionado (RDS) y Valkey (ElastiCache, compatible con Redis).
# Fiabilidad → recuperabilidad: copias automáticas de 7 días con restauración a cualquier segundo (PITR).
# Seguridad → confidencialidad: cifrado en reposo (KMS) y en tránsito obligatorio (rds.force_ssl, TLS en Valkey).

resource "random_password" "bd" {
  length  = 32
  special = false
}

resource "random_password" "cache" {
  length  = 32
  special = false # ElastiCache no admite @ " / en el token
}

resource "aws_db_subnet_group" "bd" {
  name       = local.nombre
  subnet_ids = aws_subnet.privada[*].id
}

resource "aws_db_parameter_group" "bd" {
  name   = "${local.nombre}-pg16"
  family = "postgres16"

  parameter {
    name  = "rds.force_ssl" # rechaza conexiones sin TLS
    value = "1"
  }
  parameter {
    name  = "log_min_duration_statement" # consultas > 500 ms a CloudWatch Logs (RNF-02)
    value = "500"
  }
}

# TimescaleDB no existe en RDS: la migración V2 de Flyway lo detecta y usa un índice BRIN sobre la serie temporal.
resource "aws_db_instance" "bd" {
  identifier                      = local.nombre
  engine                          = "postgres"
  engine_version                  = "16"
  instance_class                  = var.bd_clase
  allocated_storage               = var.bd_almacenamiento_gb
  max_allocated_storage           = var.bd_almacenamiento_gb * 5 # crece solo, sin parada
  storage_type                    = "gp3"
  storage_encrypted               = true
  db_name                         = "asimetria"
  username                        = "asimetria"
  password                        = random_password.bd.result
  db_subnet_group_name            = aws_db_subnet_group.bd.name
  vpc_security_group_ids          = [aws_security_group.bd.id]
  parameter_group_name            = aws_db_parameter_group.bd.name
  publicly_accessible             = false
  multi_az                        = var.bd_multi_az
  backup_retention_period         = 7
  backup_window                   = "07:00-08:00"         # 02:00 hora de Colombia
  maintenance_window              = "sun:08:30-sun:09:30" # domingo, mercado cerrado
  auto_minor_version_upgrade      = true
  copy_tags_to_snapshot           = true
  deletion_protection             = true
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${local.nombre}-final"
  enabled_cloudwatch_logs_exports = ["postgresql"]
}

resource "aws_elasticache_subnet_group" "cache" {
  name       = local.nombre
  subnet_ids = aws_subnet.privada[*].id
}

resource "aws_elasticache_replication_group" "cache" {
  replication_group_id       = local.nombre
  description                = "Cache y estado compartido entre tareas de la API"
  engine                     = "valkey"
  engine_version             = "8.0"
  parameter_group_name       = "default.valkey8"
  node_type                  = var.cache_nodo
  num_cache_clusters         = var.cache_alta_disponibilidad ? 2 : 1
  automatic_failover_enabled = var.cache_alta_disponibilidad
  multi_az_enabled           = var.cache_alta_disponibilidad
  port                       = 6379
  subnet_group_name          = aws_elasticache_subnet_group.cache.name
  security_group_ids         = [aws_security_group.cache.id]
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = random_password.cache.result
  snapshot_retention_limit   = 0 # todo lo que guarda se reconstruye desde PostgreSQL o el proveedor
  maintenance_window         = "sun:09:30-sun:10:30"
  apply_immediately          = true
}

# --- Secretos (Seguridad → confidencialidad): ECS los inyecta como variables; nunca están en la imagen ni en Git ---
resource "random_password" "jwt" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "app" {
  name                    = "${local.nombre}/app"
  description             = "Secretos generados por Terraform: BD, caché y firma JWT"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id
  secret_string = jsonencode({
    DATABASE_PASSWORD = random_password.bd.result
    REDIS_PASSWORD    = random_password.cache.result
    JWT_SECRET        = random_password.jwt.result
  })
}

# Claves de terceros: Terraform crea el contenedor vacío y NO las conoce (no quedan en el estado).
# Se cargan una vez con: aws secretsmanager put-secret-value (docs/AWS.md §4).
resource "aws_secretsmanager_secret" "integraciones" {
  name                    = "${local.nombre}/integraciones"
  description             = "ALPHAVANTAGE_API_KEY y SENDGRID_API_KEY"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "integraciones" {
  secret_id     = aws_secretsmanager_secret.integraciones.id
  secret_string = jsonencode({ ALPHAVANTAGE_API_KEY = "", SENDGRID_API_KEY = "" })
  lifecycle {
    ignore_changes = [secret_string]
  }
}
