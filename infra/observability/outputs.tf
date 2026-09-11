output "log_project_name" {
  description = "SG log project name; CN partition never ships into it (C1)."
  value       = alicloud_log_project.sg.project_name
}

output "log_store_names" {
  description = "Log stores: esb-app (SAE), audit (ActionTrail), orders-events."
  value       = keys(local.log_stores)
}

output "audit_log_store_arn" {
  description = "ARN of the audit store (ActionTrail target, Phase-5 groundwork)."
  value       = "acs:log:${var.region}:${var.account_id}:project/${alicloud_log_project.sg.project_name}/logstore/audit"
}

output "trail_name" {
  description = "ActionTrail trail writing management events into the audit store."
  value       = alicloud_actiontrail.sg.trail_name
}

output "alarm_contact_group" {
  description = "CMS contact group receiving alarm notifications."
  value       = alicloud_cms_alarm_contact_group.ops.alarm_contact_group_name
}
