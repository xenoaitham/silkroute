variable "region" {
  type        = string
  description = "Region for all data resources; RDS and OSS are region-pinned (C1: SG data never leaves the SG partition)."
  default     = "ap-southeast-1"
}

variable "vswitch_id" {
  type        = string
  description = "Private vSwitch for the RDS instance (from the network module)."
}

variable "security_group_ids" {
  type        = list(string)
  description = "Security groups attached to RDS (sg-data from the network module controls ESB/ERP-only access)."
}

variable "oss_kms_key_id" {
  type        = string
  description = "KMS key (from the security module) used for SSE-KMS on every SG bucket."
}

variable "esb_runtime_role_arn" {
  type        = string
  description = "ARN of the silkroute-esb-runtime role; the only principal granted object access in bucket policies."
}

variable "db_engine_version" {
  type        = string
  description = "RDS MySQL major version; 8.0 matches the sim (docker-compose mysql:8.0.x) per ADR-0001."
  default     = "8.0"
}

# Instance type must be availability-checked per region/AZ at apply time; the
# default is a common general-purpose v65 type but plan cannot verify stock.
variable "db_instance_type" {
  type        = string
  description = "RDS instance class. Must be availability-checked per region at apply time; plan-only mode cannot verify stock."
  default     = "mysql.n2.small.v65"
}

variable "db_instance_storage_gb" {
  type        = number
  description = "RDS storage in GB (pay-as-you-go ESSD)."
  default     = 20
}

variable "db_name" {
  type        = string
  description = "Business database for the OMS; name mirrors the sim database."
  default     = "silkroute_oms"
}

variable "db_account_username" {
  type        = string
  description = "Application account name on the OMS database (Normal type, no admin rights)."
  default     = "silkroute_oms"
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

variable "vpc_cidr" {
  description = "SG hub VPC CIDR; becomes the RDS security-IP whitelist (VPC-internal access only)."
  type        = string
}

variable "rds_kms_key_arn" {
  description = "ARN of the silkroute-sg-rds KMS key used for TDE on the SG RDS instance."
  type        = string
}
