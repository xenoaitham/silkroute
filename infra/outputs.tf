# SG-hub outputs -------------------------------------------------------------

output "vpc_id" {
  description = "SG-hub VPC."
  value       = module.network.vpc_id
}

output "esb_runtime_role_arn" {
  description = "Runtime role assumed by the ESB on SAE."
  value       = module.security.esb_runtime_role_arn
}

output "ci_deploy_role_arn" {
  description = "Role the CI pipeline assumes to deploy this landing zone."
  value       = module.security.ci_deploy_role_arn
}

output "rds_connection_string" {
  description = "SG RDS internal endpoint for SPRING_DATASOURCE_URL."
  value       = module.data.rds_connection_string
}

output "bucket_names" {
  description = "SG OSS lake + artifacts buckets."
  value       = module.data.bucket_names
}

output "kafka_bootstrap_endpoint" {
  description = "Managed value for KAFKA_BOOTSTRAP (SG)."
  value       = module.compute.kafka_bootstrap_endpoint
}

output "log_project_name" {
  description = "SG log project (audit trail target included)."
  value       = module.observability.log_project_name
}

# CN-partition outputs -------------------------------------------------------
# one(module.cn_partition[*].attr) is the plan-safe guarded form: with the
# flag false the expanded resource list is empty and these evaluate to null,
# without breaking plan. A `module.cn_partition[0].attr` ternary cannot do
# that (index 0 is invalid when count = 0).

output "cn_vpc_id" {
  description = "CN-partition VPC (null while enable_cn_region = false)."
  value       = one(module.cn_partition[*].cn_vpc_id)
}

output "cn_kms_key_arn" {
  description = "CN data-key ARN (null while enable_cn_region = false)."
  value       = one(module.cn_partition[*].cn_kms_key_arn)
}

output "cn_bucket_names" {
  description = "CN lake buckets (null while enable_cn_region = false)."
  value       = one(module.cn_partition[*].cn_bucket_names)
}

output "cn_kafka_bootstrap_endpoint" {
  description = "CN Kafka bootstrap endpoint pattern (null while enable_cn_region = false)."
  value       = one(module.cn_partition[*].cn_kafka_bootstrap_endpoint)
}
