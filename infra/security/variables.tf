variable "region" {
  type        = string
  description = "Region used when building resource ARNs for policy scoping."
  default     = "ap-southeast-1"
}

# The AliCloud account ID is unknowable in validated-plans mode (no account).
# This placeholder keeps plan credential-free; in cloud mode it must be replaced
# by the real account ID (or fed from the environment), never invented.
variable "account_id" {
  type        = string
  description = "Placeholder AliCloud account ID used to construct RAM trust principals and policy ARNs. Replace with the real 16-digit account ID once an account exists; never a wildcard."
  default     = "123456789012345678"
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

variable "kms_pending_window_days" {
  type        = number
  description = "Pending-deletion window for KMS keys (maps to pending_window_in_days); 7 days balances recovery needs against PIPL data-subject rights."
  default     = 7
}
