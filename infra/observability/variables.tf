variable "region" {
  type        = string
  description = "Region for logs, trail, and alarms; SG logs stay in the SG region (C1)."
  default     = "ap-southeast-1"
}

variable "account_id" {
  type        = string
  description = "Placeholder AliCloud account ID for ARN construction (trail SLS write role, project ARN). Replace once an account exists."
  default     = "123456789012345678"
}

variable "log_project_name" {
  type        = string
  description = "Single SG log project; the CN partition gets its own project and logs are never shipped cross-region (C1)."
  default     = "silkroute-sg"
}

# Service-linked role that lets ActionTrail write into SLS; created/activated
# out-of-band on first trail use, referenced here by ARN placeholder.
variable "actiontrail_write_role_arn" {
  type        = string
  description = "Role ActionTrail assumes to write management events into the audit log store. Service-linked role is created out-of-band; placeholder for plan."
  default     = "acs:ram::123456789012345678:role/aliyunserviceroleforactiontrail"
}

variable "rds_instance_id" {
  type        = string
  description = "RDS instance ID for the connection-count alarm; wired from the data module at root (standalone plan uses the placeholder)."
  default     = "rm-silkroute-placeholder"
}

variable "ops_webhook_url" {
  type        = string
  description = "Ops-channel webhook for SLS alert notifications (the same on-call channel the CMS contact group feeds). Placeholder for plan."
  default     = "https://hooks.placeholder.invalid/silkroute-ops"
}

variable "sls_action_policy_id" {
  type        = string
  description = "SLS action policy that routes alicloud_sls_alert notifications (DingTalk webhook -> the same ops-oncall channel). SLS action policies are console-managed at activation: provider 1.285.0 ships no action-policy resource (schema-verified), so the policy is created out-of-band and its ID replaces this placeholder — the same pattern as actiontrail_write_role_arn."
  default     = "console-managed-at-activation"
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
