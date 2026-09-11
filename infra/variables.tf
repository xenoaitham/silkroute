variable "access_key" {
  type        = string
  description = "Placeholder AliCloud access key for credential-free plan runs (ADR-0002). In a real account this comes from the environment/profile, never a default."
  default     = "tf-placeholder-access-key"
}

variable "secret_key" {
  type        = string
  description = "Placeholder AliCloud secret key for credential-free plan runs (ADR-0002). In a real account this comes from the environment/profile, never a default."
  default     = "tf-placeholder-secret-key"
}

variable "region" {
  type        = string
  description = "Singapore-hub region; the international hub for the Maple Retail Group landing zone (C2)."
  default     = "ap-southeast-1"
}

variable "vpc_cidr" {
  type        = string
  description = "SG-hub VPC CIDR (10.60.0.0/16). The CN partition uses 10.70.0.0/16 - ranges stay disjoint."
  default     = "10.60.0.0/16"
}

variable "az1" {
  type        = string
  description = "First SG availability zone (pinned so plans never call regional data sources)."
  default     = "ap-southeast-1a"
}

variable "az2" {
  type        = string
  description = "Second SG availability zone."
  default     = "ap-southeast-1b"
}

variable "admin_cidr" {
  type        = string
  description = "Office/VPN CIDR allowed to reach ESB admin endpoints. Placeholder office range; never 0.0.0.0/0."
  default     = "10.60.1.0/24"
}

variable "account_id" {
  type        = string
  description = "Placeholder AliCloud account ID used for ARN construction in policies and the ActionTrail trail. Replace with the real account ID once an account exists."
  default     = "123456789012345678"
}

variable "enable_cn_region" {
  type        = bool
  description = "Design-only switch composing the CN partition (ADR-0005). False by default; true is plan-validated and never applied from this environment."
  default     = false
}

variable "cn_region" {
  type        = string
  description = "Mainland-China region for the CN partition (C1: PIPL residency)."
  default     = "cn-beijing"
}

variable "cn_az1" {
  type        = string
  description = "First CN availability zone."
  default     = "cn-beijing-g"
}

variable "cn_az2" {
  type        = string
  description = "Second CN availability zone."
  default     = "cn-beijing-h"
}
