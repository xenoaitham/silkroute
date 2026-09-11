locals {
  # Log-store TTLs (days): app logs 30, audit 180 (ActionTrail target, the
  # "who did what" groundwork for Phase 5), order-event traces 30.
  log_stores = {
    "esb-app"       = 30
    "audit"         = 180
    "orders-events" = 30
  }
}

resource "alicloud_log_project" "sg" {
  project_name = var.log_project_name
  description  = "SilkRoute SG-hub logs. C1: Chinese-customer PII lives in the CN partition's own project and is never shipped cross-region."
  tags         = var.common_tags
}

resource "alicloud_log_store" "sg" {
  for_each = local.log_stores

  project_name     = alicloud_log_project.sg.project_name
  logstore_name    = each.key
  retention_period = each.value
  shard_count      = 2
}

# ActionTrail: management events into the audit store - the Phase-5 "who did
# what" foundation. Write role is the service-linked role (out-of-band).
resource "alicloud_actiontrail" "sg" {
  trail_name         = "silkroute-sg-trail"
  trail_region       = var.region
  event_rw           = "All"
  sls_project_arn    = "acs:log:${var.region}:${var.account_id}:project/${alicloud_log_project.sg.project_name}"
  sls_write_role_arn = var.actiontrail_write_role_arn
}

resource "alicloud_log_dashboard" "overview" {
  project_name   = alicloud_log_project.sg.project_name
  dashboard_name = "silkroute-overview"
  display_name   = "SilkRoute SG overview"
  # Two charts: ESB latency p95 on esb-app; DLQ depth on orders-events.
  char_list = jsonencode([
    {
      title = "ESB p95 latency (s)"
      type  = "linePro"
      search = {
        logstore     = "esb-app"
        topic        = ""
        query        = "* | select approx_percentile(request_time, 95) as p95, date_trunc('minute', __time__) as t group by t order by t asc limit 1440"
        start        = "-3600"
        end          = "0"
        chartType    = "line"
        timeSpanType = "Relative"
      }
      display = {
        xPosition = "t"
        yPosition = "p95"
      }
    },
    {
      title = "DLQ depth (silkroute-esb-dlq)"
      type  = "linePro"
      search = {
        logstore     = "orders-events"
        topic        = "silkroute-esb-dlq"
        query        = "* | select count(*) as dlq_depth, date_trunc('minute', __time__) as t group by t order by t asc limit 1440"
        start        = "-3600"
        end          = "0"
        chartType    = "line"
        timeSpanType = "Relative"
      }
      display = {
        xPosition = "t"
        yPosition = "dlq_depth"
      }
    },
  ])
}

resource "alicloud_cms_alarm_contact_group" "ops" {
  alarm_contact_group_name = "silkroute-ops"
  contacts                 = ["ops-oncall"]
  describe                 = "SilkRoute platform on-call group; receives CMS alarm notifications."
  enable_subscribed        = true
}

# SAE CPU > 80% for 5 minutes, per app.
resource "alicloud_cms_alarm" "sae_cpu" {
  name           = "silkroute-sae-cpu-high"
  project        = "acs_sae"
  metric         = "CPU"
  contact_groups = [alicloud_cms_alarm_contact_group.ops.alarm_contact_group_name]
  period         = 300
  metric_dimensions = jsonencode([
    { "appName" = "silkroute-esb" },
    { "appName" = "silkroute-erp" },
  ])
  effective_interval = "00:00-23:59"
  enabled            = true

  escalations_critical {
    statistics          = "Average"
    comparison_operator = ">"
    threshold           = "80"
    times               = 1
  }
}

# RDS connection count > 80 on the OMS instance.
resource "alicloud_cms_alarm" "rds_connections" {
  name           = "silkroute-rds-connections-high"
  project        = "acs_rds"
  metric         = "ConnectionUsage"
  contact_groups = [alicloud_cms_alarm_contact_group.ops.alarm_contact_group_name]
  period         = 60
  metric_dimensions = jsonencode([
    { "instanceId" = var.rds_instance_id },
  ])
  effective_interval = "00:00-23:59"
  enabled            = true

  escalations_critical {
    statistics          = "Average"
    comparison_operator = ">"
    threshold           = "80"
    times               = 1
  }
}

# DLQ depth via SLS alert on the orders-events store, filtered to the DLQ
# topic. SLS alerting has no CMS contact-group binding, so notification goes
# to the same on-call webhook; severity 8 = Critical in SLS enum.
resource "alicloud_log_alert" "dlq_depth" {
  project_name      = alicloud_log_project.sg.project_name
  alert_name        = "dlq-depth-alert"
  alert_displayname = "DLQ depth alert"
  alert_description = "Fires when silkroute-esb-dlq accumulates more than 100 messages in the window."

  query_list {
    project        = alicloud_log_project.sg.project_name
    store          = "orders-events"
    region         = var.region
    store_type     = "log"
    chart_title    = "DLQ depth"
    query          = "topic: silkroute-esb-dlq | select count(*) as dlq_depth"
    start          = "-600"
    end            = "0"
    time_span_type = "Relative"
  }

  schedule {
    type            = "FixedRate"
    interval        = "5m"
    time_zone       = "Asia/Singapore"
    run_immediately = true
  }

  severity_configurations {
    severity       = 8
    eval_condition = { "dlq_depth" = "> 100" }
  }

  group_configuration {
    type   = "no_condition"
    fields = []
  }

  notification_list {
    # Plan-time validator accepts only SMS/DingTalk/Email/MessageCenter; the
    # ops webhook rides the DingTalk channel via service_uri.
    type        = "DingTalk"
    content     = "SilkRoute SG: silkroute-esb-dlq depth exceeded 100 in the last 10 minutes."
    service_uri = var.ops_webhook_url
  }
}
