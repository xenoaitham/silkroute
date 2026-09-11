# Singapore hub = the international landing zone (C2). The CN partition is
# composed below with count = var.enable_cn_region ? 1 : 0 and is designed and
# validated but never applied from this environment (ADR-0005).

module "network" {
  source = "./network"

  region     = var.region
  vpc_cidr   = var.vpc_cidr
  az1        = var.az1
  az2        = var.az2
  admin_cidr = var.admin_cidr
}

module "security" {
  source = "./security"

  region     = var.region
  account_id = var.account_id
}

module "data" {
  source = "./data"

  region   = var.region
  vpc_cidr = var.vpc_cidr
  # RDS sits in the second AZ's private vSwitch (replica spread; the SAE apps
  # and Kafka live on private_1).
  vswitch_id           = module.network.private_vswitch_id_2
  security_group_ids   = [module.network.security_group_data_id]
  oss_kms_key_id       = module.security.oss_key_id
  rds_kms_key_arn      = module.security.rds_key_arn
  esb_runtime_role_arn = module.security.esb_runtime_role_arn
}

module "compute" {
  source = "./compute"

  region                = var.region
  vpc_id                = module.network.vpc_id
  private_vswitch_id    = module.network.private_vswitch_id_1
  esb_security_group_id = module.network.security_group_esb_id
  erp_security_group_id = module.network.security_group_erp_id
  kafka_zone_id         = var.az1
}

module "observability" {
  source = "./observability"

  region          = var.region
  account_id      = var.account_id
  rds_instance_id = module.data.rds_instance_id
}

module "cn_partition" {
  source = "./cn-partition"
  count  = var.enable_cn_region ? 1 : 0

  # Region placement is enforced HERE, at the provider graph — not by tags or
  # name strings (SEC-4-01). Without this block the module silently inherits
  # the default ap-southeast-1 provider.
  providers = { alicloud = alicloud.cn }

  cn_region = var.cn_region
  cn_az1    = var.cn_az1
  cn_az2    = var.cn_az2
}
