# Red: 2 zonas de disponibilidad (Fiabilidad → disponibilidad). Subredes públicas solo para ALB y NAT;
# API, base de datos y caché viven en subredes privadas sin IP pública (Seguridad → confidencialidad).
data "aws_availability_zones" "disponibles" {
  state = "available"
}

resource "aws_vpc" "principal" {
  cidr_block           = "10.20.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = local.nombre }
}

resource "aws_subnet" "publica" {
  count             = 2
  vpc_id            = aws_vpc.principal.id
  cidr_block        = cidrsubnet(aws_vpc.principal.cidr_block, 8, count.index)
  availability_zone = local.azs[count.index]
  tags              = { Name = "${local.nombre}-publica-${count.index}" }
}

resource "aws_subnet" "privada" {
  count             = 2
  vpc_id            = aws_vpc.principal.id
  cidr_block        = cidrsubnet(aws_vpc.principal.cidr_block, 8, 10 + count.index)
  availability_zone = local.azs[count.index]
  tags              = { Name = "${local.nombre}-privada-${count.index}" }
}

resource "aws_internet_gateway" "principal" {
  vpc_id = aws_vpc.principal.id
  tags   = { Name = local.nombre }
}

# ponytail: un NAT compartido por defecto (~32 USD/mes c/u); si su zona cae, la API sigue sirviendo pero sin
# llegar a Alpha Vantage/SendGrid. nat_por_az = true lo elimina como punto único de falla.
resource "aws_eip" "nat" {
  count  = local.nats
  domain = "vpc"
  tags   = { Name = "${local.nombre}-nat-${count.index}" }
}

resource "aws_nat_gateway" "principal" {
  count         = local.nats
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.publica[count.index].id
  tags          = { Name = "${local.nombre}-${count.index}" }
  depends_on    = [aws_internet_gateway.principal]
}

resource "aws_route_table" "publica" {
  vpc_id = aws_vpc.principal.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.principal.id
  }
  tags = { Name = "${local.nombre}-publica" }
}

resource "aws_route_table_association" "publica" {
  count          = 2
  subnet_id      = aws_subnet.publica[count.index].id
  route_table_id = aws_route_table.publica.id
}

resource "aws_route_table" "privada" {
  count  = 2
  vpc_id = aws_vpc.principal.id
  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.principal[min(count.index, local.nats - 1)].id
  }
  tags = { Name = "${local.nombre}-privada-${count.index}" }
}

resource "aws_route_table_association" "privada" {
  count          = 2
  subnet_id      = aws_subnet.privada[count.index].id
  route_table_id = aws_route_table.privada[count.index].id
}

# Endpoint gratuito: las capas de imagen de ECR se descargan de S3 sin pasar (ni pagar) por el NAT.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.principal.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.privada[*].id
  tags              = { Name = "${local.nombre}-s3" }
}

# --- Grupos de seguridad: cada capa solo acepta tráfico de la anterior (mínimo privilegio) ---
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group" "alb" {
  name        = "${local.nombre}-alb"
  description = "ALB: HTTPS solo desde CloudFront"
  vpc_id      = aws_vpc.principal.id
}

resource "aws_vpc_security_group_ingress_rule" "alb_cloudfront" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS desde los nodos de CloudFront"
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "alb_api" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.api.id
  ip_protocol                  = "tcp"
  from_port                    = 8000
  to_port                      = 8000
}

resource "aws_security_group" "api" {
  name        = "${local.nombre}-api"
  description = "Tareas ECS de la API"
  vpc_id      = aws_vpc.principal.id
}

resource "aws_vpc_security_group_ingress_rule" "api_alb" {
  security_group_id            = aws_security_group.api.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8000
  to_port                      = 8000
}

resource "aws_vpc_security_group_egress_rule" "api_salida" {
  security_group_id = aws_security_group.api.id
  description       = "BD, caché, ECR, Secrets Manager, Alpha Vantage y SendGrid"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "bd" {
  name        = "${local.nombre}-bd"
  description = "PostgreSQL solo desde la API"
  vpc_id      = aws_vpc.principal.id
}

resource "aws_vpc_security_group_ingress_rule" "bd_api" {
  security_group_id            = aws_security_group.bd.id
  referenced_security_group_id = aws_security_group.api.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_security_group" "cache" {
  name        = "${local.nombre}-cache"
  description = "Valkey solo desde la API"
  vpc_id      = aws_vpc.principal.id
}

resource "aws_vpc_security_group_ingress_rule" "cache_api" {
  security_group_id            = aws_security_group.cache.id
  referenced_security_group_id = aws_security_group.api.id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
}
