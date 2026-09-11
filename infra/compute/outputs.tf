output "sae_namespace_id" {
  description = "SAE namespace ID (region:name format)."
  value       = alicloud_sae_namespace.sg.namespace_id
}

output "esb_application_id" {
  description = "SAE application ID for the ESB."
  value       = alicloud_sae_application.esb.id
}

output "erp_application_id" {
  description = "SAE application ID for the legacy ERP."
  value       = alicloud_sae_application.erp.id
}

output "kafka_instance_id" {
  description = "AliCloud Kafka instance ID."
  value       = alicloud_alikafka_instance.sg.id
}

output "kafka_bootstrap_endpoint" {
  description = "Bootstrap endpoint pattern (VPC default endpoint + 9092); the managed value for KAFKA_BOOTSTRAP."
  value       = "${alicloud_alikafka_instance.sg.domain_endpoint}:9092"
}

output "kafka_topic_names" {
  description = "Topic names, identical to the sim (ADR-0001 parity)."
  value       = [alicloud_alikafka_topic.orders_events.topic, alicloud_alikafka_topic.esb_dlq.topic]
}

output "kafka_sasl_username" {
  description = "Kafka SASL PLAIN username for the ESB (password lives in random_password/secret store)."
  value       = alicloud_alikafka_sasl_user.esb_client.username
}
