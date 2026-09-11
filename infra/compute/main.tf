# SAE namespace + applications (ADR-0004: SAE over ACK for pay-per-use) ------

resource "alicloud_sae_namespace" "sg" {
  namespace_id          = "${var.region}:silkroute-sg"
  namespace_name        = "silkroute-sg"
  namespace_description = "Singapore-hub SAE namespace for the ESB and legacy ERP (ADR-0004)."
}

# envs keys mirror the sim (docker-compose) exactly - the ADR-0001 swap rule:
# managed deployment changes configuration only, never app code.

resource "alicloud_sae_application" "esb" {
  app_name        = "silkroute-esb"
  app_description = "ESB (Spring Boot + Camel); parity port of apps/esb from the sim."
  namespace_id    = alicloud_sae_namespace.sg.namespace_id
  # Schema-exact enum in 1.285.0 is "FatJar" (not "Jar").
  package_type      = "FatJar"
  package_url       = var.esb_jar_url
  replicas          = var.app_replicas
  cpu               = var.app_cpu_millicores
  memory            = var.app_memory_mb
  timezone          = "Asia/Singapore"
  vpc_id            = var.vpc_id
  vswitch_id        = var.private_vswitch_id
  security_group_id = var.esb_security_group_id
  tags              = var.common_tags

  # envs is a JSON-encoded string in provider 1.285.0 (schema type "string").
  # KAFKA_ORDERS_TOPIC/KAFKA_DLQ_TOPIC select the dot-free ApsaraMQ topic names
  # (dots are illegal in ApsaraMQ topics; the sim keeps its dotted defaults).
  envs = jsonencode({
    KAFKA_BOOTSTRAP     = var.kafka_bootstrap
    KAFKA_ORDERS_TOPIC  = "silkroute-orders-events"
    KAFKA_DLQ_TOPIC     = "silkroute-esb-dlq"
    REDIS_HOST          = var.redis_host
    REDIS_PORT          = var.redis_port
    ERP_BASEURL         = var.erp_baseurl
    ERP_WSS_USERNAME    = var.erp_wss_username
    ERP_WSS_PASSWORD    = var.erp_wss_password
    ESB_FAULT_INJECTION = "false"
  })
}

resource "alicloud_sae_application" "erp" {
  app_name          = "silkroute-erp"
  app_description   = "Legacy SOAP ERP; parity port of apps/legacy-erp from the sim."
  namespace_id      = alicloud_sae_namespace.sg.namespace_id
  package_type      = "FatJar"
  package_url       = var.erp_jar_url
  replicas          = var.app_replicas
  cpu               = var.app_cpu_millicores
  memory            = var.app_memory_mb
  timezone          = "Asia/Singapore"
  vpc_id            = var.vpc_id
  vswitch_id        = var.private_vswitch_id
  security_group_id = var.erp_security_group_id
  tags              = var.common_tags

  envs = jsonencode({
    ERP_WSS_USERNAME = var.erp_wss_username
    ERP_WSS_PASSWORD = var.erp_wss_password
  })
}

# Kafka (AliCloud Message Queue for Apache Kafka) -----------------------------
# deploy_type 4 = VPC instance. ApsaraMQ for Kafka CreateTopic allows ONLY
# letters, digits, "_" and "-" in topic names (3-64 chars) — dots are legal in
# vanilla Kafka but rejected by the managed service, so cloud topic names are
# dot-free and selected via the KAFKA_ORDERS_TOPIC / KAFKA_DLQ_TOPIC env vars
# (env-indirection is the ADR-0001 swap mechanism; the sim keeps its dotted
# defaults). Validated by scripts/tf-apply-validity.sh in CI.

resource "alicloud_alikafka_instance" "sg" {
  name        = "silkroute-sg-kafka"
  deploy_type = 4
  disk_size   = var.kafka_disk_size_gb
  disk_type   = 0
  spec_type   = "normal"
  paid_type   = "PostPaid"
  vpc_id      = var.vpc_id
  vswitch_id  = var.private_vswitch_id
  zone_id     = var.kafka_zone_id
  tags        = var.common_tags
}

resource "alicloud_alikafka_topic" "orders_events" {
  instance_id   = alicloud_alikafka_instance.sg.id
  topic         = "silkroute-orders-events"
  partition_num = 12
  remark        = "Order lifecycle events; dot-free ApsaraMQ name of the sim's silkroute.orders.events (selected via KAFKA_ORDERS_TOPIC)."
}

resource "alicloud_alikafka_topic" "esb_dlq" {
  instance_id   = alicloud_alikafka_instance.sg.id
  topic         = "silkroute-esb-dlq"
  partition_num = 6
  remark        = "ESB dead-letter queue; dot-free ApsaraMQ name of the sim's silkroute.esb.dlq (selected via KAFKA_DLQ_TOPIC)."
}

resource "random_password" "kafka_sasl" {
  length  = 24
  special = false
}

resource "alicloud_alikafka_sasl_user" "esb_client" {
  instance_id = alicloud_alikafka_instance.sg.id
  username    = "esb-client"
  password    = random_password.kafka_sasl.result
  type        = "plain"
  # Password is generated, never literal; in cloud mode it would be handed to
  # the ESB via secret store injection, and state would be access-restricted.
}
