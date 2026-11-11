# Infraestructura de Asimetría en AWS (docs/AWS.md). Cada recurso indica la característica ISO/IEC 25010 que atiende.
terraform {
  required_version = ">= 1.10"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 6.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
  # Estado remoto en S3 cifrado, versionado y con bloqueo nativo (use_lockfile): terraform init -backend-config=backend.hcl
  backend "s3" {}
}

provider "aws" {
  region = var.region
  default_tags { tags = local.etiquetas }
}

# CloudFront solo acepta certificados ACM y Web ACL de WAF creados en us-east-1.
provider "aws" {
  alias  = "global"
  region = "us-east-1"
  default_tags { tags = local.etiquetas }
}

locals {
  nombre  = "${var.proyecto}-${var.entorno}"
  origen  = "origen.${var.dominio}" # nombre del ALB; solo CloudFront lo usa
  azs     = slice(data.aws_availability_zones.disponibles.names, 0, 2)
  nats    = var.nat_por_az ? 2 : 1
  familia = "${local.nombre}-api"
  etiquetas = {
    Proyecto      = var.proyecto
    Entorno       = var.entorno
    GestionadoPor = "terraform"
    Norma         = "ISO-25010"
  }
}
