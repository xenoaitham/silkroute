# CN network: private-only. No NAT/EIP on purpose - the CN partition has no
# egress design yet and nothing here may publish a public endpoint (C1/C2).

resource "alicloud_vpc" "cn" {
  cidr_block  = var.cn_vpc_cidr
  vpc_name    = "silkroute-cn-mainland"
  description = "CN-partition VPC (PIPL-restricted, ADR-0005 validated-plan-only)"
  tags        = local.cn_tags
}

resource "alicloud_vswitch" "cn_private_1" {
  cidr_block   = var.cn_private_vswitch1_cidr
  vpc_id       = alicloud_vpc.cn.id
  zone_id      = var.cn_az1
  vswitch_name = "silkroute-cn-private-1"
  description  = "CN SAE compute and Kafka"
  tags         = local.cn_tags
}

resource "alicloud_vswitch" "cn_private_2" {
  cidr_block   = var.cn_private_vswitch2_cidr
  vpc_id       = alicloud_vpc.cn.id
  zone_id      = var.cn_az2
  vswitch_name = "silkroute-cn-private-2"
  description  = "CN RDS in second AZ"
  tags         = local.cn_tags
}

# No inbound rules yet: the CN ESB topology is finalized at CN-partition
# activation time. Closed-by-default beats a speculative open rule.
resource "alicloud_security_group" "cn_esb" {
  security_group_name = "sg-cn-esb"
  vpc_id              = alicloud_vpc.cn.id
  description         = "CN ESB security group; intentionally empty until the CN partition activation design lands (ADR-0005)."
  security_group_type = "normal"
  tags                = local.cn_tags
}

# CN data key: one KMS key for TDE + OSS SSE in the CN region, aliased for
# policy/reference stability. CN keys never leave cn-beijing (C1).
resource "alicloud_kms_key" "cn_data" {
  description            = "CN-partition master key: RDS TDE + OSS SSE-KMS for silkroute-cn-* buckets. PII key material stays in cn-beijing."
  pending_window_in_days = 7
  key_usage              = "ENCRYPT/DECRYPT"
  tags                   = local.cn_tags
}

resource "alicloud_kms_alias" "cn_data" {
  alias_name = "alias/silkroute-cn-data"
  key_id     = alicloud_kms_key.cn_data.id
}

# CN RDS: same MySQL 8.0/utf8mb4/UTC rules as SG (C5), with TDE enabled - the
# CN partition holds the restricted PII tier, so at-rest encryption is not
# optional here.
resource "alicloud_db_instance" "cn_oms" {
  engine                   = "MySQL"
  engine_version           = "8.0"
  instance_type            = "mysql.n2.small.v65"
  instance_storage         = 20
  db_instance_storage_type = "cloud_essd"
  instance_charge_type     = "Postpaid"
  instance_name            = "silkroute-cn-rds"
  vswitch_id               = alicloud_vswitch.cn_private_2.id
  security_group_ids       = [alicloud_security_group.cn_esb.id]
  # Whitelist the CN VPC only; never 0.0.0.0/0 (SEC-4-09).
  security_ips = [var.cn_vpc_cidr]
  tde_status   = "Enabled"
  # TDE key pinned to the CN partition's own key — never the SG keys (C1).
  tde_encryption_key = alicloud_kms_key.cn_data.arn
  tags               = local.cn_tags

  parameters {
    name  = "character_set_server"
    value = "utf8mb4"
  }

  parameters {
    name  = "time_zone"
    value = "+00:00"
  }
}

resource "alicloud_db_database" "cn_oms" {
  instance_id    = alicloud_db_instance.cn_oms.id
  data_base_name = "silkroute_oms"
  character_set  = "utf8mb4"
  description    = "CN OMS database; holds China-customer PII (pipl-restricted)."
}

resource "random_password" "cn_oms_app" {
  length  = 16
  special = false
}

resource "alicloud_db_account" "cn_oms_app" {
  db_instance_id   = alicloud_db_instance.cn_oms.id
  account_name     = "silkroute_oms"
  account_password = random_password.cn_oms_app.result
  account_type     = "Normal"
  # Password via random generator here; in cloud mode injected from the CN
  # secret store (KMS-encrypted), never a literal.
}

resource "alicloud_db_account_privilege" "cn_oms_app" {
  instance_id  = alicloud_db_instance.cn_oms.id
  account_name = alicloud_db_account.cn_oms_app.account_name
  db_names     = [alicloud_db_database.cn_oms.data_base_name]
  privilege    = "ReadWrite"
}

locals {
  cn_buckets = ["silkroute-cn-bronze", "silkroute-cn-silver", "silkroute-cn-gold"]
}

resource "alicloud_oss_bucket" "cn_lake" {
  for_each = toset(local.cn_buckets)

  bucket        = each.value
  force_destroy = false
  tags          = local.cn_tags
}

resource "alicloud_oss_bucket_server_side_encryption" "cn_lake" {
  for_each = alicloud_oss_bucket.cn_lake

  bucket            = each.value.id
  sse_algorithm     = "KMS"
  kms_master_key_id = alicloud_kms_key.cn_data.id
}

resource "alicloud_oss_bucket_versioning" "cn_lake" {
  for_each = alicloud_oss_bucket.cn_lake

  bucket = each.value.id
  status = "Enabled"
}

resource "alicloud_oss_bucket_public_access_block" "cn_lake" {
  for_each = alicloud_oss_bucket.cn_lake

  bucket              = each.value.id
  block_public_access = true
}

# No ESB grant yet (the CN runtime role is defined at CN activation); the
# constant is the plaintext-transport deny. Principal ["*"] appears only in
# this Deny. NO cross-region replication on any CN bucket (C1).
resource "alicloud_oss_bucket_policy" "cn_lake" {
  for_each = alicloud_oss_bucket.cn_lake

  bucket = each.value.id
  policy = jsonencode({
    Version = "1"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Action    = ["oss:GetObject", "oss:PutObject", "oss:DeleteObject"]
        Principal = ["*"]
        Resource = [
          "acs:oss:*:*:${each.value.bucket}",
          "acs:oss:*:*:${each.value.bucket}/*",
        ]
        Condition = {
          Bool = {
            "acs:SecureTransport" = ["false"]
          }
        }
      }
    ]
  })
}

# CN SAE namespace + CN-pinned ESB --------------------------------------------

resource "alicloud_sae_namespace" "cn" {
  namespace_id          = "${var.cn_region}:silkroute-cn"
  namespace_name        = "silkroute-cn"
  namespace_description = "CN-partition SAE namespace; PII workload stays in mainland China (C1)."
}

resource "alicloud_sae_application" "esb_cn" {
  app_name          = "silkroute-esb-cn"
  app_description   = "CN ESB replica; serves mainland-China traffic, no cross-border calls."
  namespace_id      = alicloud_sae_namespace.cn.namespace_id
  package_type      = "FatJar"
  package_url       = var.cn_esb_jar_url
  replicas          = var.cn_app_replicas
  cpu               = 500
  memory            = 1024
  timezone          = "Asia/Shanghai"
  vpc_id            = alicloud_vpc.cn.id
  vswitch_id        = alicloud_vswitch.cn_private_1.id
  security_group_id = alicloud_security_group.cn_esb.id
  tags              = local.cn_tags

  # Every endpoint below resolves inside the CN partition - C1.
  envs = jsonencode({
    KAFKA_BOOTSTRAP     = var.cn_kafka_bootstrap
    REDIS_HOST          = var.cn_redis_endpoint
    REDIS_PORT          = var.cn_redis_port
    ERP_BASEURL         = var.cn_erp_baseurl
    ERP_WSS_USERNAME    = var.cn_erp_wss_username
    ERP_WSS_PASSWORD    = var.cn_erp_wss_password
    ESB_FAULT_INJECTION = "false"
  })
}

# CN Kafka: identical topic names to SG and to the sim (ADR-0001 parity).
resource "alicloud_alikafka_instance" "cn" {
  name        = "silkroute-cn-kafka"
  deploy_type = 4
  disk_size   = var.cn_kafka_disk_size_gb
  disk_type   = "0"
  spec_type   = "normal"
  paid_type   = "PostPaid"
  vpc_id      = alicloud_vpc.cn.id
  vswitch_id  = alicloud_vswitch.cn_private_1.id
  zone_id     = var.cn_az1
  tags        = local.cn_tags
}

resource "alicloud_alikafka_topic" "cn_orders_events" {
  instance_id   = alicloud_alikafka_instance.cn.id
  topic         = "silkroute.orders.events"
  partition_num = 12
  remark        = "CN order events; same topic name as SG/sim, separate broker, no replication to SG (C1)."
  tags          = local.cn_tags
}

resource "alicloud_alikafka_topic" "cn_esb_dlq" {
  instance_id   = alicloud_alikafka_instance.cn.id
  topic         = "silkroute.esb.dlq"
  partition_num = 6
  remark        = "CN ESB DLQ; same topic name as SG/sim, no cross-region replication (C1)."
  tags          = local.cn_tags
}
