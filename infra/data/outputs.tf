output "rds_instance_id" {
  description = "RDS MySQL instance ID."
  value       = alicloud_db_instance.oms.id
}

output "rds_connection_string" {
  description = "Internal connection string for SPRING_DATASOURCE_URL (VPC endpoint)."
  value       = alicloud_db_instance.oms.connection_string
}

output "rds_port" {
  description = "RDS MySQL port."
  value       = alicloud_db_instance.oms.port
}

output "database_name" {
  description = "OMS database name on the RDS instance."
  value       = alicloud_db_database.oms.data_base_name
}

output "bucket_names" {
  description = "SG lake + artifacts bucket names (static, policy-scoped)."
  value       = keys(local.esb_bucket_grants)
}

output "rds_account_name" {
  description = "Application DB account name (password never leaves random_password/KMS)."
  value       = alicloud_db_account.oms_app.account_name
}
