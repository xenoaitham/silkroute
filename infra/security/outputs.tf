output "oss_key_id" {
  description = "KMS key used for OSS SSE-KMS."
  value       = alicloud_kms_key.oss.id
}

output "oss_key_arn" {
  description = "ARN of the OSS SSE master key; consumed by the data module and by the ESB runtime policy."
  value       = alicloud_kms_key.oss.arn
}

output "rds_key_arn" {
  description = "ARN of the RDS TDE master key; consumed by the data module."
  value       = alicloud_kms_key.rds.arn
}

output "esb_runtime_role_name" {
  description = "RAM role assumed by SAE for the ESB application."
  value       = alicloud_ram_role.esb_runtime.role_name
}

output "esb_runtime_role_arn" {
  description = "ARN of the ESB runtime role; used in OSS bucket policies to grant S3-style object access."
  value       = alicloud_ram_role.esb_runtime.arn
}

output "ci_user_name" {
  description = "RAM user for the CI pipeline (no access keys in IaC)."
  value       = alicloud_ram_user.ci.name
}

output "ci_deploy_role_arn" {
  description = "Role ARN the CI pipeline assumes to deploy the landing zone."
  value       = alicloud_ram_role.ci_deploy.arn
}
