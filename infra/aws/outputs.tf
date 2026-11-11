output "url" {
  value = "https://${var.dominio}"
}

# Copiar tal cual en GitHub → Settings → Environments → aws-produccion → Variables (docs/AWS.md §5).
output "variables_github" {
  value = {
    AWS_ROLE_ARN    = aws_iam_role.github.arn
    AWS_REGION      = var.region
    ECR_REPOSITORIO = aws_ecr_repository.api.name
    ECS_CLUSTER     = aws_ecs_cluster.principal.name
    ECS_SERVICIO    = aws_ecs_service.api.name
    ECS_FAMILIA     = local.familia
    BUCKET_WEB      = aws_s3_bucket.web.bucket
    CLOUDFRONT_ID   = aws_cloudfront_distribution.web.id
    DOMINIO_AWS     = var.dominio
  }
}

output "secreto_integraciones" {
  description = "Donde cargar ALPHAVANTAGE_API_KEY y SENDGRID_API_KEY"
  value       = aws_secretsmanager_secret.integraciones.name
}

output "logs_api" {
  value = aws_cloudwatch_log_group.api.name
}
