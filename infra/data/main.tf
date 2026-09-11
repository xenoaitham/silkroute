locals {
  # Bucket names stay static (no env suffix) so RAM policy resources can pin
  # exact ARNs instead of name prefixes.
  lake_buckets = ["silkroute-sg-artifacts", "silkroute-sg-bronze", "silkroute-sg-silver", "silkroute-sg-gold"]

  # ESB runtime access is least-privilege per bucket: read-only on artifacts,
  # read/write on bronze. Silver/gold are analytics outputs the ESB never
  # touches. Every policy also denies plaintext-transport access to everyone.
  esb_bucket_grants = {
    "silkroute-sg-artifacts" = ["oss:GetObject", "oss:ListObjects"]
    "silkroute-sg-bronze"    = ["oss:GetObject", "oss:PutObject", "oss:DeleteObject", "oss:ListObjects"]
    "silkroute-sg-silver"    = []
    "silkroute-sg-gold"      = []
  }
}

# RDS MySQL ---------------------------------------------------------------
# Postpaid (pay-as-you-go) per the Phase-4 cost guardrails. UTC storage is the
# C5 multi-timezone rule: all money timestamps persist in UTC; presentation
# timezones (Canada/Saskatchewan, Asia/Singapore, Asia/Shanghai) are applied at
# the app/batch-scheduling layer, never in the database session.

resource "alicloud_db_instance" "oms" {
  engine                   = "MySQL"
  engine_version           = var.db_engine_version
  instance_type            = var.db_instance_type
  instance_storage         = var.db_instance_storage_gb
  db_instance_storage_type = "cloud_essd"
  # RDS casing is "Postpaid" (provider-validated values: Postpaid/Prepaid/Serverless).
  instance_charge_type = "Postpaid"
  instance_name        = "silkroute-sg-rds"
  vswitch_id           = var.vswitch_id
  security_group_ids   = var.security_group_ids
  # Whitelist the VPC only (SEC-4-09); never widen this to 0.0.0.0/0.
  security_ips = [var.vpc_cidr]
  # At-rest encryption is not optional in the SG tier either (PDPA-relevant
  # customer data) — key from the security module, mirror of the CN instance
  # (SEC-4-08).
  tde_status         = "Enabled"
  tde_encryption_key = var.rds_kms_key_arn
  tags               = var.common_tags

  parameters {
    name  = "character_set_server"
    value = "utf8mb4"
  }

  parameters {
    name  = "time_zone"
    value = "+00:00"
  }
}

resource "alicloud_db_database" "oms" {
  instance_id    = alicloud_db_instance.oms.id
  data_base_name = var.db_name
  character_set  = "utf8mb4"
  description    = "Order-management database; schema-parity with sim-mysql (Phase 3)."
}

resource "random_password" "oms_app" {
  length  = 16
  special = false
}

resource "alicloud_db_account" "oms_app" {
  db_instance_id   = alicloud_db_instance.oms.id
  account_name     = var.db_account_username
  account_password = random_password.oms_app.result
  account_type     = "Normal"
  # In cloud mode this password would come from KMS/secret manager
  # (kms_encrypted_password) or be injected at deploy time - never a literal,
  # and state access would be restricted accordingly.
}

resource "alicloud_db_account_privilege" "oms_app" {
  instance_id  = alicloud_db_instance.oms.id
  account_name = alicloud_db_account.oms_app.account_name
  db_names     = [alicloud_db_database.oms.data_base_name]
  privilege    = "ReadWrite"
}

# OSS lake + artifacts ------------------------------------------------------
# Every bucket: SSE-KMS with the landing-zone key, versioning on, public
# access blocked, and a bucket policy that denies any request arriving over
# plaintext. No cross-region replication anywhere (C1: Chinese-customer PII
# stays in its region).

resource "alicloud_oss_bucket" "lake" {
  for_each = toset(local.lake_buckets)

  # acl omitted on purpose: the deprecated `acl` argument is replaced by the
  # alicloud_oss_bucket_acl resource, and "private" is already the OSS default.
  bucket        = each.value
  force_destroy = false
  tags          = var.common_tags
}

resource "alicloud_oss_bucket_server_side_encryption" "lake" {
  for_each = alicloud_oss_bucket.lake

  bucket            = each.value.id
  sse_algorithm     = "KMS"
  kms_master_key_id = var.oss_kms_key_id
}

resource "alicloud_oss_bucket_versioning" "lake" {
  for_each = alicloud_oss_bucket.lake

  bucket = each.value.id
  status = "Enabled"
}

resource "alicloud_oss_bucket_public_access_block" "lake" {
  for_each = alicloud_oss_bucket.lake

  bucket              = each.value.id
  block_public_access = true
}

locals {
  # Bucket policy per bucket: an Allow statement only when the ESB needs the
  # bucket, plus the same Deny-plaintext statement. Principal ["*"] appears
  # solely in the Deny (deny everyone over insecure transport) - action lists
  # stay explicit and resources exact.
  bucket_policies = {
    for name, grants in local.esb_bucket_grants : name => jsonencode({
      Version = "1"
      Statement = concat(
        length(grants) > 0 ? [
          {
            Sid       = "AllowEsbRuntime"
            Effect    = "Allow"
            Action    = concat(grants, ["oss:GetBucketInfo"])
            Principal = [var.esb_runtime_role_arn]
            Resource = concat(
              ["acs:oss:*:*:${name}"],
              contains(grants, "oss:GetObject") ? ["acs:oss:*:*:${name}/*"] : [],
            )
          }
        ] : [],
        [
          {
            Sid       = "DenyInsecureTransport"
            Effect    = "Deny"
            Action    = ["oss:GetObject", "oss:PutObject", "oss:DeleteObject"]
            Principal = ["*"]
            Resource = [
              "acs:oss:*:*:${name}",
              "acs:oss:*:*:${name}/*",
            ]
            Condition = {
              Bool = {
                "acs:SecureTransport" = ["false"]
              }
            }
          }
        ]
      )
    })
  }
}

resource "alicloud_oss_bucket_policy" "lake" {
  for_each = alicloud_oss_bucket.lake

  bucket = each.value.id
  policy = local.bucket_policies[each.key]
}
