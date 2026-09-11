output "cn_vpc_id" {
  description = "CN-partition VPC ID."
  value       = alicloud_vpc.cn.id
}

output "cn_private_vswitch_ids" {
  description = "CN private vSwitches."
  value       = [alicloud_vswitch.cn_private_1.id, alicloud_vswitch.cn_private_2.id]
}

output "cn_kms_key_arn" {
  description = "ARN of the CN data key (TDE + SSE-KMS), pinned to the CN region."
  value       = alicloud_kms_key.cn_data.arn
}

output "cn_bucket_names" {
  description = "CN lake bucket names (pipl-restricted)."
  value       = local.cn_buckets
}

output "cn_rds_connection_string" {
  description = "CN RDS internal endpoint."
  value       = alicloud_db_instance.cn_oms.connection_string
}

output "cn_kafka_bootstrap_endpoint" {
  description = "CN Kafka bootstrap endpoint pattern."
  value       = "${alicloud_alikafka_instance.cn.domain_endpoint}:9092"
}

output "cn_esb_application_id" {
  description = "CN ESB SAE application ID."
  value       = alicloud_sae_application.esb_cn.id
}
