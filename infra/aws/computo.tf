# API en ECS Fargate detrás de un ALB, con autoescalado (Eficiencia → capacidad; Fiabilidad → disponibilidad).

resource "aws_ecr_repository" "api" {
  name                 = "${var.proyecto}-api"
  image_tag_mutability = "IMMUTABLE" # un sha = una imagen: despliegues reproducibles y rollback exacto
  image_scanning_configuration {
    scan_on_push = true # CVE conocidos en cada imagen (Seguridad)
  }
  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "api" {
  repository = aws_ecr_repository.api.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Conserva las 20 imagenes mas recientes (rollback)"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 20 }
      action       = { type = "expire" }
    }]
  })
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${local.nombre}/api"
  retention_in_days = 30 # la auditoría de negocio (RNF-04, 90 días) vive en PostgreSQL
}

resource "aws_ecs_cluster" "principal" {
  name = local.nombre
  setting {
    name  = "containerInsights" # CPU, memoria y red por tarea (Mantenibilidad → analizabilidad)
    value = "enabled"
  }
}

data "aws_iam_policy_document" "ecs_asumir" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Rol de ejecución: solo descargar la imagen, escribir logs y leer los dos secretos de la app.
# La API no llama a servicios de AWS, por eso no tiene rol de tarea (mínimo privilegio).
resource "aws_iam_role" "ejecucion" {
  name               = "${local.nombre}-ecs-ejecucion"
  assume_role_policy = data.aws_iam_policy_document.ecs_asumir.json
}

resource "aws_iam_role_policy_attachment" "ejecucion" {
  role       = aws_iam_role.ejecucion.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "ejecucion_secretos" {
  name = "leer-secretos"
  role = aws_iam_role.ejecucion.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "secretsmanager:GetSecretValue"
      Resource = [aws_secretsmanager_secret.app.arn, aws_secretsmanager_secret.integraciones.arn]
    }]
  })
}

locals {
  entorno_api = {
    DATABASE_URL           = "jdbc:postgresql://${aws_db_instance.bd.address}:5432/asimetria?sslmode=verify-full&sslrootcert=/app/rds-ca.pem"
    DATABASE_USER          = "asimetria"
    DB_POOL                = "10" # api_max × 10 conexiones < max_connections de db.t4g.micro (~80)
    REDIS_HOST             = aws_elasticache_replication_group.cache.primary_endpoint_address
    REDIS_SSL              = "true"
    PROVEEDOR              = var.proveedor
    INTERVALO_COTIZACIONES = var.proveedor == "alphavantage" ? "60" : "5"
    MODO_DEMO              = tostring(var.modo_demo)
    HERRAMIENTAS_PRUEBA    = "false" # nunca en producción: la inyección de fallos afecta a todos
    CONFIAR_PROXY          = "true"  # la IP real llega en CloudFront-Viewer-Address
    CORREO_REMITENTE       = var.correo_remitente
  }
  secretos_api = {
    DATABASE_PASSWORD    = "${aws_secretsmanager_secret.app.arn}:DATABASE_PASSWORD::"
    REDIS_PASSWORD       = "${aws_secretsmanager_secret.app.arn}:REDIS_PASSWORD::"
    JWT_SECRET           = "${aws_secretsmanager_secret.app.arn}:JWT_SECRET::"
    ALPHAVANTAGE_API_KEY = "${aws_secretsmanager_secret.integraciones.arn}:ALPHAVANTAGE_API_KEY::"
    SENDGRID_API_KEY     = "${aws_secretsmanager_secret.integraciones.arn}:SENDGRID_API_KEY::"
  }
}

resource "aws_ecs_task_definition" "api" {
  family                   = local.familia
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.api_cpu
  memory                   = var.api_memoria
  execution_role_arn       = aws_iam_role.ejecucion.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  volume {
    name = "tmp" # único directorio escribible (Tomcat); el resto del sistema de archivos es de solo lectura
  }
  container_definitions = jsonencode([{
    name                   = "api"
    image                  = "${aws_ecr_repository.api.repository_url}:${var.imagen_tag}"
    essential              = true
    readonlyRootFilesystem = true
    stopTimeout            = 30 # apagado ordenado (server.shutdown: graceful)
    portMappings           = [{ containerPort = 8000, protocol = "tcp" }]
    mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp" }]
    environment            = [for k, v in local.entorno_api : { name = k, value = v }]
    secrets                = [for k, v in local.secretos_api : { name = k, valueFrom = v }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.api.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "api"
      }
    }
  }])
}

resource "aws_lb" "api" {
  name                       = "${local.nombre}-api"
  load_balancer_type         = "application"
  internal                   = false
  security_groups            = [aws_security_group.alb.id]
  subnets                    = aws_subnet.publica[*].id
  drop_invalid_header_fields = true
  enable_deletion_protection = true
}

resource "aws_lb_target_group" "api" {
  name                 = "${local.nombre}-api"
  port                 = 8000
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.principal.id
  deregistration_delay = 30
  health_check {
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# CloudFront → ALB solo admite hasta TLS 1.2, por eso la política acepta 1.3 y 1.2 (sin versiones antiguas).
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.api.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.origen.certificate_arn
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "application/json"
      message_body = "{\"detail\":\"Acceso directo no permitido\"}"
      status_code  = "403"
    }
  }
}

# Solo se reenvía lo que trae la cabecera secreta que añade CloudFront: nadie se salta el WAF yendo directo al ALB.
resource "random_password" "origen" {
  length  = 48
  special = false
}

resource "aws_lb_listener_rule" "desde_cloudfront" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
  condition {
    http_header {
      http_header_name = "X-Origen-Verificado"
      values           = [random_password.origen.result]
    }
  }
}

resource "aws_ecs_service" "api" {
  name                               = "api"
  cluster                            = aws_ecs_cluster.principal.id
  task_definition                    = aws_ecs_task_definition.api.arn
  desired_count                      = var.api_min
  launch_type                        = "FARGATE"
  health_check_grace_period_seconds  = 120 # arranque de la JVM + Flyway + carga inicial
  propagate_tags                     = "SERVICE"
  deployment_minimum_healthy_percent = 100 # despliegue sin pérdida de capacidad
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true # si la nueva versión no queda sana, ECS vuelve sola a la anterior
  }
  network_configuration {
    subnets          = aws_subnet.privada[*].id
    security_groups  = [aws_security_group.api.id]
    assign_public_ip = false
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8000
  }
  lifecycle {
    ignore_changes = [task_definition, desired_count] # los gestionan el despliegue (CI) y el autoescalado
  }
  depends_on = [aws_lb_listener_rule.desde_cloudfront]
}

resource "aws_appautoscaling_target" "api" {
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.principal.name}/${aws_ecs_service.api.name}"
  min_capacity       = var.api_min
  max_capacity       = var.api_max
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "cpu-60"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.api.service_namespace
  scalable_dimension = aws_appautoscaling_target.api.scalable_dimension
  resource_id        = aws_appautoscaling_target.api.resource_id
  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_out_cooldown = 60
    scale_in_cooldown  = 300
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

resource "aws_appautoscaling_policy" "peticiones" {
  name               = "peticiones-por-tarea"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.api.service_namespace
  scalable_dimension = aws_appautoscaling_target.api.scalable_dimension
  resource_id        = aws_appautoscaling_target.api.resource_id
  target_tracking_scaling_policy_configuration {
    target_value       = 3000 # peticiones por tarea y minuto (~50 req/s)
    scale_out_cooldown = 60
    scale_in_cooldown  = 300
    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${aws_lb.api.arn_suffix}/${aws_lb_target_group.api.arn_suffix}"
    }
  }
}
