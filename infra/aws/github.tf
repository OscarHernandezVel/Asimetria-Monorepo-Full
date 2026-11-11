# Despliegue continuo sin claves de larga duración: GitHub Actions obtiene credenciales temporales por OIDC.
# Solo el entorno "aws-produccion" del repositorio puede asumir el rol (se le pueden exigir revisores en GitHub).

resource "aws_iam_openid_connect_provider" "github" {
  count          = var.crear_oidc_github ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.crear_oidc_github ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

locals {
  oidc_github = var.crear_oidc_github ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn
}

data "aws_iam_policy_document" "github_asumir" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.oidc_github]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:environment:aws-produccion"]
    }
  }
}

resource "aws_iam_role" "github" {
  name                 = "${local.nombre}-github-despliegue"
  assume_role_policy   = data.aws_iam_policy_document.github_asumir.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "github_permisos" {
  statement {
    sid       = "LoginECR"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    sid = "SubirImagen"
    actions = [
      "ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:CompleteLayerUpload", "ecr:DescribeImages",
      "ecr:InitiateLayerUpload", "ecr:PutImage", "ecr:UploadLayerPart",
    ]
    resources = [aws_ecr_repository.api.arn]
  }
  statement {
    sid       = "DefinicionDeTarea" # estas acciones no admiten restricción por recurso
    actions   = ["ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition"]
    resources = ["*"]
  }
  statement {
    sid       = "ActualizarServicio"
    actions   = ["ecs:DescribeServices", "ecs:UpdateService"]
    resources = [aws_ecs_service.api.id]
  }
  statement {
    sid       = "PasarRolDeEjecucion"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.ejecucion.arn]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
  statement {
    sid       = "PublicarWeb"
    actions   = ["s3:ListBucket", "s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = [aws_s3_bucket.web.arn, "${aws_s3_bucket.web.arn}/*"]
  }
  statement {
    sid       = "InvalidarCache"
    actions   = ["cloudfront:CreateInvalidation"]
    resources = [aws_cloudfront_distribution.web.arn]
  }
}

resource "aws_iam_role_policy" "github" {
  name   = "desplegar"
  role   = aws_iam_role.github.id
  policy = data.aws_iam_policy_document.github_permisos.json
}
