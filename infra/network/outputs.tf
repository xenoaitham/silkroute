output "vpc_id" {
  description = "ID of the Singapore-hub VPC."
  value       = alicloud_vpc.hub.id
}

output "private_vswitch_ids" {
  description = "Private vSwitches for compute (SAE, Kafka) and data workloads."
  value       = [alicloud_vswitch.private_1.id, alicloud_vswitch.private_2.id]
}

output "private_vswitch_id_1" {
  description = "First private vSwitch; SAE apps and Kafka are pinned here."
  value       = alicloud_vswitch.private_1.id
}

output "public_vswitch_id" {
  description = "Public vSwitch hosting the NAT gateway."
  value       = alicloud_vswitch.public.id
}

output "security_group_esb_id" {
  description = "Security group attached to the ESB SAE application."
  value       = alicloud_security_group.esb.id
}

output "security_group_erp_id" {
  description = "Security group attached to the legacy ERP SAE application."
  value       = alicloud_security_group.erp.id
}

output "security_group_data_id" {
  description = "Security group attached to RDS and Redis."
  value       = alicloud_security_group.data.id
}

output "nat_eip_id" {
  description = "EIP allocation bound to the NAT gateway."
  value       = alicloud_eip.nat.id
}
