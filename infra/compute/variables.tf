variable "region" {
  type        = string
  description = "Region for SAE and Kafka resources."
  default     = "ap-southeast-1"
}

variable "vpc_id" {
  type        = string
  description = "Hub VPC (from the network module)."
}

variable "private_vswitch_id" {
  type        = string
  description = "Private vSwitch for SAE apps and the Kafka instance; no public ingress path exists."
}

variable "esb_security_group_id" {
  type        = string
  description = "sg-esb, attached to the ESB SAE application."
}

variable "erp_security_group_id" {
  type        = string
  description = "sg-erp, attached to the legacy ERP SAE application."
}

variable "kafka_zone_id" {
  type        = string
  description = "AZ for the Kafka instance (pinned, no data sources - see ADR-0002)."
  default     = "ap-southeast-1a"
}

variable "app_replicas" {
  type        = number
  description = "Replica count per SAE application; 2 covers both AZs for the pay-per-use footprint (ADR-0004)."
  default     = 2
}

variable "app_cpu_millicores" {
  type        = number
  description = "SAE CPU per replica in millicores (500 = 0.5 vCore)."
  default     = 500
}

variable "app_memory_mb" {
  type        = number
  description = "SAE memory per replica in MB."
  default     = 1024
}

variable "esb_jar_url" {
  type        = string
  description = "OSS URL of the ESB fat jar; uploaded out-of-band by CI (never in IaC)."
  default     = "oss://silkroute-sg-artifacts/esb/esb-1.0.0-SNAPSHOT.jar"
}

variable "erp_jar_url" {
  type        = string
  description = "OSS URL of the legacy ERP jar; uploaded out-of-band by CI (never in IaC)."
  default     = "oss://silkroute-sg-artifacts/erp/erp-1.0.0-SNAPSHOT.jar"
}

# --- env-var placeholders (sim -> managed parity, ADR-0001) -----------------
# Every value below is injected from a secret store / deploy pipeline in cloud
# mode; the placeholders exist only so `terraform plan` runs credential-free.

variable "kafka_bootstrap" {
  type        = string
  description = "KAFKA_BOOTSTRAP for the ESB; in cloud mode the AliCloud Kafka VPC endpoint (see kafka_bootstrap_endpoint output). Placeholder for plan only."
  default     = "127.0.0.1:39092"
}

variable "redis_host" {
  type        = string
  description = "REDIS_HOST for the ESB; in cloud mode the SG ApsaraDB for Redis VPC endpoint. Injected from the deploy pipeline; placeholder for plan only."
  default     = "127.0.0.1"
}

variable "redis_port" {
  type        = string
  description = "REDIS_PORT for the ESB. Injected from the deploy pipeline; placeholder for plan only (sim uses 16379, managed Redis uses 6379)."
  default     = "6379"
}

variable "erp_baseurl" {
  type        = string
  description = "ERP_BASEURL for the ESB: internal SAE endpoint of the ERP app. Placeholder for plan only."
  default     = "http://silkroute-erp:18080"
}

variable "erp_wss_username" {
  type        = string
  description = "ERP_WSS_USERNAME for ESB<->ERP WSS auth. Injected from secret store at deploy; placeholder for plan only."
  default     = "wss-placeholder-user"
}

variable "erp_wss_password" {
  type        = string
  description = "ERP_WSS_PASSWORD for ESB<->ERP WSS auth. Injected from secret store at deploy; placeholder for plan only."
  default     = "wss-placeholder-pass"
}

variable "kafka_disk_size_gb" {
  type        = number
  description = "Kafka broker disk in GB (PostPaid floor is 300 GB for the smart spec family)."
  default     = 300
}

variable "common_tags" {
  type        = map(string)
  description = "Tags applied to every taggable resource (Project/ManagedBy are mandatory across the landing zone)."
  default = {
    Project   = "silkroute"
    ManagedBy = "terraform"
    Partition = "sg-hub"
  }
}
