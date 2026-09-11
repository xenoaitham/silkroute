variable "cn_region" {
  type        = string
  description = "China mainland region hosting the CN partition (C1: PII never leaves this region)."
  default     = "cn-beijing"
}

variable "cn_az1" {
  type        = string
  description = "First CN availability zone (pinned; no regional data sources per ADR-0002)."
  default     = "cn-beijing-g"
}

variable "cn_az2" {
  type        = string
  description = "Second CN availability zone."
  default     = "cn-beijing-h"
}

variable "cn_vpc_cidr" {
  type        = string
  description = "CN VPC CIDR; 10.70.0.0/16 is reserved for CN so it never overlaps the SG hub (10.60.0.0/16)."
  default     = "10.70.0.0/16"
}

variable "cn_private_vswitch1_cidr" {
  type        = string
  description = "CN private vSwitch for SAE and Kafka."
  default     = "10.70.1.0/24"
}

variable "cn_private_vswitch2_cidr" {
  type        = string
  description = "CN private vSwitch for RDS."
  default     = "10.70.2.0/24"
}

variable "cn_kafka_disk_size_gb" {
  type        = number
  description = "CN Kafka broker disk in GB; smaller than SG because the CN footprint is minimal."
  default     = 300
}

variable "cn_app_replicas" {
  type        = number
  description = "Replicas for the CN ESB (small scale by design)."
  default     = 1
}

# --- CN endpoint placeholders -------------------------------------------------
# Envs point ONLY at CN endpoints (C1). In cloud mode these are injected from
# the CN deploy pipeline / secret store; placeholders exist for plan only.

variable "cn_redis_endpoint" {
  type        = string
  description = "REDIS_HOST for the CN ESB: CN ApsaraDB for Redis endpoint inside the CN VPC. Placeholder for plan."
  default     = "10.70.1.10"
}

variable "cn_redis_port" {
  type        = string
  description = "REDIS_PORT for the CN ESB."
  default     = "6379"
}

variable "cn_kafka_bootstrap" {
  type        = string
  description = "KAFKA_BOOTSTRAP for the CN ESB: CN alikafka endpoint (see kafka_bootstrap_endpoint output). Placeholder for plan."
  default     = "127.0.0.1:39092"
}

variable "cn_erp_baseurl" {
  type        = string
  description = "ERP_BASEURL for the CN ESB: the CN ERP instance endpoint. No CN traffic crosses to the SG hub (C1). Placeholder for plan."
  default     = "http://silkroute-erp-cn:18080"
}

variable "cn_erp_wss_username" {
  type        = string
  description = "ERP_WSS_USERNAME for the CN ESB<->ERP pair. Injected from secret store at deploy; placeholder for plan."
  default     = "cn-wss-placeholder-user"
}

variable "cn_erp_wss_password" {
  type        = string
  description = "ERP_WSS_PASSWORD for the CN ESB<->ERP pair. Injected from secret store at deploy; placeholder for plan."
  default     = "cn-wss-placeholder-pass"
}

variable "cn_esb_jar_url" {
  type        = string
  description = "OSS URL of the CN ESB fat jar inside the CN artifacts path; uploaded out-of-band."
  default     = "oss://silkroute-cn-bronze/apps/esb/esb-1.0.0-SNAPSHOT.jar"
}

locals {
  # C1 machine-checkable residency tags, on EVERY resource in this module.
  cn_tags = {
    Project             = "silkroute"
    ManagedBy           = "terraform"
    Partition           = "cn-mainland"
    residency           = "cn"
    data-classification = "pipl-restricted"
  }
}
