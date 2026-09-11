# AZ IDs are hardcoded via variables instead of alicloud_zones/alicloud_regions
# data sources: in validated-plans mode (ADR-0002) a plan must complete with no
# AliCloud API calls, and those data sources are API-calling.

variable "region" {
  type        = string
  description = "Region the Singapore hub is deployed in. Pinning region here keeps every resource aligned with the provider-level region without regional data sources."
  default     = "ap-southeast-1"
}

variable "vpc_cidr" {
  type        = string
  description = "CIDR block of the Singapore-hub VPC. 10.60.0.0/16 is reserved for SG; the CN partition uses 10.70.0.0/16 so the ranges never overlap if peered later."
  default     = "10.60.0.0/16"
}

variable "az1" {
  type        = string
  description = "First availability zone for the private compute/data vSwitches (verified against the region at apply time)."
  default     = "ap-southeast-1a"
}

variable "az2" {
  type        = string
  description = "Second availability zone; keeps SAE replicas and RDS deployable across zones."
  default     = "ap-southeast-1b"
}

variable "public_vswitch_cidr" {
  type        = string
  description = "CIDR of the public vSwitch hosting the NAT gateway only. No compute lands here."
  default     = "10.60.0.0/24"
}

variable "private_vswitch1_cidr" {
  type        = string
  description = "CIDR of the first private vSwitch (SAE apps, Kafka, Redis)."
  default     = "10.60.1.0/24"
}

variable "private_vswitch2_cidr" {
  type        = string
  description = "CIDR of the second private vSwitch (RDS and replicas in the second AZ)."
  default     = "10.60.2.0/24"
}

variable "admin_cidr" {
  type        = string
  description = "Office/VPN CIDR allowed to reach the ESB admin endpoints. Defaults to the placeholder office range; never widen to 0.0.0.0/0."
  default     = "10.60.1.0/24"
}

variable "nat_spec" {
  type        = string
  description = "Enhanced NAT gateway specification (Small is the cheapest tier; scaled via this variable)."
  default     = "Small"
}
