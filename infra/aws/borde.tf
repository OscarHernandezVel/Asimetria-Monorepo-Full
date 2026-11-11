# Borde: Route 53 + ACM + CloudFront (HTTP/3, TLS ≥ 1.2, caché global de la web) + WAF.
# Un solo dominio sirve la web (S3) y la API (/api/* → ALB): mismo origen, sin CORS, igual que con Nginx.

data "aws_route53_zone" "zona" {
  name         = var.zona_route53
  private_zone = false
}

# --- Certificados (renovación automática por ACM) ---
resource "aws_acm_certificate" "web" {
  provider          = aws.global
  domain_name       = var.dominio
  validation_method = "DNS"
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_acm_certificate" "origen" {
  domain_name       = local.origen
  validation_method = "DNS"
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "validacion_web" {
  for_each = {
    for o in aws_acm_certificate.web.domain_validation_options : o.domain_name => o
  }
  zone_id         = data.aws_route53_zone.zona.zone_id
  name            = each.value.resource_record_name
  type            = each.value.resource_record_type
  records         = [each.value.resource_record_value]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_route53_record" "validacion_origen" {
  for_each = {
    for o in aws_acm_certificate.origen.domain_validation_options : o.domain_name => o
  }
  zone_id         = data.aws_route53_zone.zona.zone_id
  name            = each.value.resource_record_name
  type            = each.value.resource_record_type
  records         = [each.value.resource_record_value]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "web" {
  provider                = aws.global
  certificate_arn         = aws_acm_certificate.web.arn
  validation_record_fqdns = [for r in aws_route53_record.validacion_web : r.fqdn]
}

resource "aws_acm_certificate_validation" "origen" {
  certificate_arn         = aws_acm_certificate.origen.arn
  validation_record_fqdns = [for r in aws_route53_record.validacion_origen : r.fqdn]
}

resource "aws_route53_record" "origen" {
  zone_id = data.aws_route53_zone.zona.zone_id
  name    = local.origen
  type    = "A"
  alias {
    name                   = aws_lb.api.dns_name
    zone_id                = aws_lb.api.zone_id
    evaluate_target_health = true
  }
}

resource "aws_route53_record" "web" {
  for_each = toset(["A", "AAAA"])
  zone_id  = data.aws_route53_zone.zona.zone_id
  name     = var.dominio
  type     = each.key
  alias {
    name                   = aws_cloudfront_distribution.web.domain_name
    zone_id                = aws_cloudfront_distribution.web.hosted_zone_id
    evaluate_target_health = false
  }
}

# --- Web estática en S3 privado (solo CloudFront la lee, mediante OAC) ---
resource "aws_s3_bucket" "web" {
  bucket_prefix = "${local.nombre}-web-"
}

resource "aws_s3_bucket_public_access_block" "web" {
  bucket                  = aws_s3_bucket.web.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "web" {
  bucket = aws_s3_bucket.web.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "web" {
  bucket = aws_s3_bucket.web.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Versionado: volver a la web anterior es restaurar objetos, no reconstruir (Fiabilidad → recuperabilidad).
resource "aws_s3_bucket_versioning" "web" {
  bucket = aws_s3_bucket.web.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "web" {
  bucket = aws_s3_bucket.web.id
  rule {
    id     = "versiones-antiguas"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}

data "aws_iam_policy_document" "web" {
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.web.arn}/*"]
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.web.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "web" {
  bucket     = aws_s3_bucket.web.id
  policy     = data.aws_iam_policy_document.web.json
  depends_on = [aws_s3_bucket_public_access_block.web]
}

# --- CloudFront ---
resource "aws_cloudfront_origin_access_control" "web" {
  name                              = "${local.nombre}-web"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_cloudfront_cache_policy" "optimizada" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_response_headers_policy" "seguridad_api" {
  name = "Managed-SecurityHeadersPolicy"
}

# La API responde Cache-Control: no-store (Spring Security), así que nada se cachea. Authorization va en la clave
# solo para que CloudFront la reenvíe; con TTL máximo de 1 s y la clave por token, nunca se mezclan usuarios.
resource "aws_cloudfront_cache_policy" "api" {
  name        = "${local.nombre}-api"
  comment     = "API: sin cache efectiva, reenvia Authorization y query strings"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 1
  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_gzip   = true
    enable_accept_encoding_brotli = true
    headers_config {
      header_behavior = "whitelist"
      headers {
        items = ["Authorization"]
      }
    }
    query_strings_config {
      query_string_behavior = "all"
    }
    cookies_config {
      cookie_behavior = "none"
    }
  }
}

# Sin Host (el ALB ve origen.<dominio> y su certificado coincide). CloudFront-Viewer-Address = IP real del cliente
# para la auditoría (RNF-04) y el límite de tasa por IP de la API.
resource "aws_cloudfront_origin_request_policy" "api" {
  name    = "${local.nombre}-api"
  comment = "API: cabeceras necesarias e IP del cliente"
  headers_config {
    header_behavior = "whitelist"
    headers {
      items = ["Accept", "Accept-Language", "Content-Type", "Origin", "Referer", "User-Agent", "CloudFront-Viewer-Address"]
    }
  }
  query_strings_config {
    query_string_behavior = "none" # ya viajan por la política de caché
  }
  cookies_config {
    cookie_behavior = "none"
  }
}

# Mismas cabeceras que apps/web/nginx.conf, más HSTS de 1 año.
resource "aws_cloudfront_response_headers_policy" "web" {
  name = "${local.nombre}-web"
  security_headers_config {
    content_security_policy {
      content_security_policy = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
      override                = true
    }
    content_type_options {
      override = true
    }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "same-origin"
      override        = true
    }
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = false
      override                   = true
    }
  }
}

resource "aws_cloudfront_distribution" "web" {
  enabled             = true
  comment             = "Asimetria: web (S3) + API (ALB)"
  aliases             = [var.dominio]
  default_root_object = "index.html"
  http_version        = "http2and3"
  is_ipv6_enabled     = true
  price_class         = "PriceClass_100" # Norteamérica y Europa (Colombia se sirve desde Miami, ~50 ms); la más barata
  web_acl_id          = aws_wafv2_web_acl.borde.arn

  origin {
    origin_id                = "web"
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
  }

  origin {
    origin_id   = "api"
    domain_name = local.origen
    custom_origin_config {
      http_port                = 80
      https_port               = 443
      origin_protocol_policy   = "https-only"
      origin_ssl_protocols     = ["TLSv1.2"]
      origin_read_timeout      = 30
      origin_keepalive_timeout = 30
    }
    custom_header {
      name  = "X-Origen-Verificado"
      value = random_password.origen.result
    }
  }

  default_cache_behavior {
    target_origin_id           = "web"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.optimizada.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.web.id
  }

  dynamic "ordered_cache_behavior" {
    for_each = ["/api/*", "/health", "/docs", "/swagger-ui/*", "/openapi.json*"]
    content {
      path_pattern               = ordered_cache_behavior.value
      target_origin_id           = "api"
      viewer_protocol_policy     = "https-only"
      allowed_methods            = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      cached_methods             = ["GET", "HEAD"]
      compress                   = true
      cache_policy_id            = aws_cloudfront_cache_policy.api.id
      origin_request_policy_id   = aws_cloudfront_origin_request_policy.api.id
      response_headers_policy_id = data.aws_cloudfront_response_headers_policy.seguridad_api.id
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.web.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

# --- WAF en el borde (Seguridad → integridad; Fiabilidad → disponibilidad ante abuso) ---
# Complementa el límite de tasa de la propia API (LimiteTasaFiltro): el WAF corta antes de que llegue a pagar cómputo.
resource "aws_wafv2_web_acl" "borde" {
  provider = aws.global
  name     = local.nombre
  scope    = "CLOUDFRONT"

  default_action {
    allow {}
  }

  rule {
    name     = "limite-autenticacion"
    priority = 0
    action {
      block {}
    }
    statement {
      rate_based_statement {
        limit                 = 100 # por IP cada 5 min contra /api/v1/auth/ (fuerza bruta)
        aggregate_key_type    = "IP"
        evaluation_window_sec = 300
        scope_down_statement {
          byte_match_statement {
            search_string         = "/api/v1/auth/"
            positional_constraint = "STARTS_WITH"
            field_to_match {
              uri_path {}
            }
            text_transformation {
              priority = 0
              type     = "LOWERCASE"
            }
          }
        }
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "limite-autenticacion"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "limite-general"
    priority = 1
    action {
      block {}
    }
    statement {
      rate_based_statement {
        limit                 = 3000 # ≈ 10 req/s sostenidas por IP
        aggregate_key_type    = "IP"
        evaluation_window_sec = 300
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "limite-general"
      sampled_requests_enabled   = true
    }
  }

  dynamic "rule" {
    for_each = {
      "AWSManagedRulesAmazonIpReputationList" = 2
      "AWSManagedRulesCommonRuleSet"          = 3
      "AWSManagedRulesKnownBadInputsRuleSet"  = 4
      "AWSManagedRulesSQLiRuleSet"            = 5
    }
    content {
      name     = rule.key
      priority = rule.value
      override_action {
        none {}
      }
      statement {
        managed_rule_group_statement {
          vendor_name = "AWS"
          name        = rule.key
        }
      }
      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = rule.key
        sampled_requests_enabled   = true
      }
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = local.nombre
    sampled_requests_enabled   = true
  }
}
