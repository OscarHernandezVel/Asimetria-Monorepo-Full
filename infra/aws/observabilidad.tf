# Alarmas → SNS → correo (Fiabilidad → disponibilidad, RNF-05; Mantenibilidad → analizabilidad).
# Los logs JSON de la API (ECS) se consultan con CloudWatch Logs Insights, p. ej.:
#   fields @timestamp, message | filter `log.level` = "ERROR" | sort @timestamp desc

resource "aws_sns_topic" "alarmas" {
  name = "${local.nombre}-alarmas"
}

resource "aws_sns_topic_subscription" "correo" {
  count     = var.correo_alarmas == "" ? 0 : 1
  topic_arn = aws_sns_topic.alarmas.arn
  protocol  = "email"
  endpoint  = var.correo_alarmas
}

locals {
  dim_alb = { LoadBalancer = aws_lb.api.arn_suffix }
  dim_tg  = { LoadBalancer = aws_lb.api.arn_suffix, TargetGroup = aws_lb_target_group.api.arn_suffix }
  dim_ecs = { ClusterName = aws_ecs_cluster.principal.name, ServiceName = aws_ecs_service.api.name }
  dim_bd  = { DBInstanceIdentifier = aws_db_instance.bd.identifier }

  alarmas = {
    "api-errores-5xx" = {
      descripcion = "Más de 10 respuestas 5xx de la API en 5 minutos"
      espacio     = "AWS/ApplicationELB", metrica = "HTTPCode_Target_5XX_Count", estadistica = "Sum"
      dimensiones = local.dim_alb, periodo = 300, evaluaciones = 1, umbral = 10, comparacion = "GreaterThanThreshold"
    }
    "api-latencia-p95" = {
      descripcion = "RNF-01: P95 de la API por encima de 2 s durante 5 minutos"
      espacio     = "AWS/ApplicationELB", metrica = "TargetResponseTime", estadistica = "p95"
      dimensiones = local.dim_alb, periodo = 60, evaluaciones = 5, umbral = 2, comparacion = "GreaterThanThreshold"
    }
    "api-tareas-no-sanas" = {
      descripcion = "RNF-05: alguna tarea de la API no pasa el health check"
      espacio     = "AWS/ApplicationELB", metrica = "UnHealthyHostCount", estadistica = "Maximum"
      dimensiones = local.dim_tg, periodo = 60, evaluaciones = 3, umbral = 0, comparacion = "GreaterThanThreshold"
    }
    "api-sin-tareas" = {
      descripcion = "RNF-05: ninguna tarea sana, el servicio está caído"
      espacio     = "AWS/ApplicationELB", metrica = "HealthyHostCount", estadistica = "Minimum"
      dimensiones = local.dim_tg, periodo = 60, evaluaciones = 2, umbral = 1, comparacion = "LessThanThreshold"
    }
    "api-memoria" = {
      descripcion = "Memoria de las tareas > 90 %: posible fuga o tamaño de tarea insuficiente"
      espacio     = "AWS/ECS", metrica = "MemoryUtilization", estadistica = "Average"
      dimensiones = local.dim_ecs, periodo = 300, evaluaciones = 3, umbral = 90, comparacion = "GreaterThanThreshold"
    }
    "bd-cpu" = {
      descripcion = "CPU de PostgreSQL > 80 % durante 15 minutos"
      espacio     = "AWS/RDS", metrica = "CPUUtilization", estadistica = "Average"
      dimensiones = local.dim_bd, periodo = 300, evaluaciones = 3, umbral = 80, comparacion = "GreaterThanThreshold"
    }
    "bd-almacenamiento" = {
      descripcion = "Menos de 2 GB libres en PostgreSQL"
      espacio     = "AWS/RDS", metrica = "FreeStorageSpace", estadistica = "Minimum"
      dimensiones = local.dim_bd, periodo = 300, evaluaciones = 1, umbral = 2147483648, comparacion = "LessThanThreshold"
    }
    "bd-conexiones" = {
      descripcion = "Conexiones a PostgreSQL cerca del máximo (api_max × DB_POOL)"
      espacio     = "AWS/RDS", metrica = "DatabaseConnections", estadistica = "Maximum"
      dimensiones = local.dim_bd, periodo = 300, evaluaciones = 2, umbral = 70, comparacion = "GreaterThanThreshold"
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "alarma" {
  for_each            = local.alarmas
  alarm_name          = "${local.nombre}-${each.key}"
  alarm_description   = each.value.descripcion
  namespace           = each.value.espacio
  metric_name         = each.value.metrica
  dimensions          = each.value.dimensiones
  period              = each.value.periodo
  evaluation_periods  = each.value.evaluaciones
  threshold           = each.value.umbral
  comparison_operator = each.value.comparacion
  # p95 es una estadística extendida; el resto, estándar
  statistic          = startswith(each.value.estadistica, "p") ? null : each.value.estadistica
  extended_statistic = startswith(each.value.estadistica, "p") ? each.value.estadistica : null
  treat_missing_data = each.key == "api-sin-tareas" ? "breaching" : "notBreaching"
  alarm_actions      = [aws_sns_topic.alarmas.arn]
  ok_actions         = [aws_sns_topic.alarmas.arn]
}

# RNF-05: sondeo externo de /health cada 30 s desde varias regiones (SLA 99.5 %), con su propia alarma.
resource "aws_route53_health_check" "salud" {
  fqdn              = var.dominio
  type              = "HTTPS"
  resource_path     = "/health"
  port              = 443
  request_interval  = 30
  failure_threshold = 3
  tags              = { Name = "${local.nombre}-salud" }
}

# Las métricas de los health checks de Route 53 solo existen en us-east-1.
resource "aws_sns_topic" "alarmas_global" {
  provider = aws.global
  name     = "${local.nombre}-alarmas-global"
}

resource "aws_sns_topic_subscription" "correo_global" {
  provider  = aws.global
  count     = var.correo_alarmas == "" ? 0 : 1
  topic_arn = aws_sns_topic.alarmas_global.arn
  protocol  = "email"
  endpoint  = var.correo_alarmas
}

resource "aws_cloudwatch_metric_alarm" "salud" {
  provider            = aws.global
  alarm_name          = "${local.nombre}-sitio-caido"
  alarm_description   = "RNF-05: /health no responde desde Internet"
  namespace           = "AWS/Route53"
  metric_name         = "HealthCheckStatus"
  dimensions          = { HealthCheckId = aws_route53_health_check.salud.id }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alarmas_global.arn]
  ok_actions          = [aws_sns_topic.alarmas_global.arn]
}
