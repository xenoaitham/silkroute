locals {
  # Log-store TTLs (days): app logs 30, audit 180 (ActionTrail target, the
  # "who did what" groundwork for Phase 5), order-event traces 30, pipeline
  # metrics 30. pipeline-metrics is the DESIGNED home for the Phase-3 CDC/batch
  # freshness metric (C4); the store ships ahead of its producer and is empty
  # until Phase 3 lands — the freshness dashboard panel and the SLO report say
  # so explicitly rather than implying a live freshness signal.
  log_stores = {
    "esb-app"          = 30
    "audit"            = 180
    "orders-events"    = 30
    "pipeline-metrics" = 30
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

# Indexes for the query-facing stores. Without at least a full-text index an
# SLS query returns nothing, so shipping the dashboards/alerts without indexes
# would be a design gap, not an activation detail. Field indexes carry the
# exact fields the dashboard/alert SQL below references; index token/tuning
# details are verified at activation (validated-plans blind spot, war-story 18
# discipline: a green plan proves schema, not SLS behavior).
resource "alicloud_log_store_index" "esb_app" {
  project  = alicloud_log_project.sg.project_name
  logstore = alicloud_log_store.sg["esb-app"].logstore_name

  full_text {
    case_sensitive  = false
    include_chinese = false
    token           = ",;\"'()[]{}?@&<>=#:-"
  }

  # p95 latency field used by the overview dashboard and the C3 budget alert.
  field_search {
    name             = "request_time"
    type             = "double"
    enable_analytics = true
  }
}

resource "alicloud_log_store_index" "orders_events" {
  project  = alicloud_log_project.sg.project_name
  logstore = alicloud_log_store.sg["orders-events"].logstore_name

  full_text {
    case_sensitive  = false
    include_chinese = false
    token           = ",;\"'()[]{}?@&<>=#:-"
  }
}

resource "alicloud_log_store_index" "audit" {
  project  = alicloud_log_project.sg.project_name
  logstore = alicloud_log_store.sg["audit"].logstore_name

  full_text {
    case_sensitive  = false
    include_chinese = false
    token           = ",;\"'()[]{}?@&<>=#:-"
  }

  # ActionTrail delivery: the event payload lands as the `event` JSON field
  # (compliance/audit-trail-design.md §2-§3); doc_value enables the SQL
  # json_extract_scalar() aggregation the denied-burst/tripwire alerts use.
  field_search {
    name             = "event"
    type             = "json"
    enable_analytics = true
  }
}

resource "alicloud_log_store_index" "pipeline_metrics" {
  project  = alicloud_log_project.sg.project_name
  logstore = alicloud_log_store.sg["pipeline-metrics"].logstore_name

  full_text {
    case_sensitive  = false
    include_chinese = false
    token           = ",;\"'()[]{}?@&<>=#:-"
  }

  # Phase-3 producer contract (design): JSON events shaped
  # {"metric":"cdc_freshness_seconds"|"batch_completion","value":<number>,
  #  "pipeline":"cdc"|"batch"} so the freshness panel and the C4 SLO read one
  # name. The producer does not exist yet — this is the contract it will write.
  field_search {
    name             = "metric"
    type             = "text"
    enable_analytics = true
  }

  field_search {
    name             = "value"
    type             = "double"
    enable_analytics = true
  }
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
  # Three charts: ESB latency p95 on esb-app; DLQ depth on orders-events;
  # CDC freshness on pipeline-metrics (DESIGN-ONLY — awaiting its Phase 3
  # producer, see the store comment and docs/slo-report.md SLO-6).
  char_list = jsonencode([
    {
      title = "ESB p95 latency (ms)"
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
    {
      # C4 (freshness <= 15 min) has NO producer yet: Phase 3 (CDC/batch) is not
      # built. This panel renders nothing until the pipeline-metrics contract
      # above starts receiving events — that is the honest label, not a gap to
      # paper over.
      title = "CDC freshness (s) — awaiting Phase 3 producer (C4, design-only)"
      type  = "linePro"
      search = {
        logstore     = "pipeline-metrics"
        topic        = ""
        query        = "metric: cdc_freshness_seconds | select max(value) as freshness_s, date_trunc('minute', __time__) as t group by t order by t asc limit 1440"
        start        = "-86400"
        end          = "0"
        chartType    = "line"
        timeSpanType = "Relative"
      }
      display = {
        xPosition = "t"
        yPosition = "freshness_s"
      }
    },
  ])
}

# Contacts (e.g. ops-oncall) are console-managed at activation; the group itself is IaC.
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

# --- SLS alerts (modern alicloud_sls_alert form) ---
#
# All four route notifications through the SLS action policy referenced by
# var.sls_action_policy_id (DingTalk webhook -> the same ops-oncall channel the
# CMS group covers). Provider 1.285.0 has NO action-policy resource
# (schema-verified), so the policy is console-managed at activation - same
# out-of-band pattern as the ActionTrail write role. Condition-expression
# SEMANTICS are verified against the live alert editor at activation
# (validated-plans proves schema, not SLS expression behavior).
#
# The former alicloud_log_alert "dlq-depth-alert" (deprecated notification_list
# form, ADR-0004 deferred follow-up) is REPLACED by alicloud_sls_alert.dlq_depth.

# DLQ depth > 100 in 10 min on the orders-events store, filtered to the DLQ
# topic. Severity 8 = Critical in the SLS enum.
resource "alicloud_sls_alert" "dlq_depth" {
  project_name = alicloud_log_project.sg.project_name
  alert_name   = "dlq-depth-alert"
  display_name = "DLQ depth alert"
  description  = "Fires when silkroute-esb-dlq accumulates more than 100 messages in the window. Migrated from the deprecated alicloud_log_alert form (ADR-0004 follow-up)."

  configuration {
    severity_configurations {
      severity = 8
      eval_condition {
        condition = "dlq_depth > 100"
      }
    }

    group_configuration {
      type   = "no_group"
      fields = []
    }

    policy_configuration {
      action_policy_id = var.sls_action_policy_id
      repeat_interval  = "5m"
    }

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
  }

  schedule {
    type     = "FixedRate"
    interval = "5m"
    # Explicit timezone per C5 (multi-timezone batch windows are scheduled
    # timezone-explicit; alert windows are no exception).
    time_zone = "Asia/Singapore"
    # Provider 1.285.0 spells this attribute `run_immdiately` (schema-verified
    # typo in the provider, not in this file).
    run_immdiately = true
  }
}

# C3 budget breach: order-mediation p95 over the trailing 5 minutes exceeding
# the 300 ms budget. The query assumes the esb-app latency metric is emitted in
# MILLISECONDS (p95_ms); the producer's unit convention is confirmed at
# activation and the threshold adjusted once, if needed (documented blind spot).
resource "alicloud_sls_alert" "p95_budget_breach" {
  project_name = alicloud_log_project.sg.project_name
  alert_name   = "esb-p95-budget-breach"
  display_name = "ESB p95 budget breach (C3)"
  description  = "Fires when the sync SOAP-mediation p95 exceeds the 300 ms C3 budget over the trailing 5 minutes. Measured value on sim hardware: docs/slo-report.md SLO-1."

  configuration {
    severity_configurations {
      severity = 8
      eval_condition {
        condition = "p95_ms > 300"
      }
    }

    group_configuration {
      type   = "no_group"
      fields = []
    }

    policy_configuration {
      action_policy_id = var.sls_action_policy_id
      repeat_interval  = "15m"
    }

    query_list {
      project        = alicloud_log_project.sg.project_name
      store          = "esb-app"
      region         = var.region
      store_type     = "log"
      chart_title    = "ESB p95"
      query          = "* | select approx_percentile(request_time, 95) as p95_ms"
      start          = "-300"
      end            = "0"
      time_span_type = "Relative"
    }
  }

  schedule {
    type           = "FixedRate"
    interval       = "5m"
    time_zone      = "Asia/Singapore"
    run_immdiately = true
  }
}

# Denied-action burst: > 10 DENIED management calls (non-empty errorCode) in 5
# minutes - the burst signal from compliance/audit-trail-design.md SS5. Who did
# what from where is the analyst refinement (SS3 Q2); the alert is the tripwire
# that makes someone run it.
resource "alicloud_sls_alert" "denied_burst" {
  project_name = alicloud_log_project.sg.project_name
  alert_name   = "audit-denied-action-burst"
  display_name = "Denied-action burst"
  description  = "Fires when more than 10 denied management actions (non-empty errorCode) occur in 5 minutes (audit-trail-design.md S5)."

  configuration {
    severity_configurations {
      severity = 8
      eval_condition {
        condition = "denied_count > 10"
      }
    }

    group_configuration {
      type   = "no_group"
      fields = []
    }

    policy_configuration {
      action_policy_id = var.sls_action_policy_id
      repeat_interval  = "15m"
    }

    query_list {
      project        = alicloud_log_project.sg.project_name
      store          = "audit"
      region         = var.region
      store_type     = "log"
      chart_title    = "Denied actions"
      query          = "* | select count(*) as denied_count from log where json_extract_scalar(event, '$.errorCode') is not null"
      start          = "-300"
      end            = "0"
      time_span_type = "Relative"
    }
  }

  schedule {
    type           = "FixedRate"
    interval       = "5m"
    time_zone      = "Asia/Singapore"
    run_immdiately = true
  }
}

# Trail-tamper tripwire: ANY StopLogging/DeleteTrail event of ANY count - the
# automated zero-tolerance tripwire for STRIDE finding F-05 named in
# compliance/audit-trail-design.md S5. An attacker turning off the audit trail
# is exactly one event, so the condition is >= 1.
resource "alicloud_sls_alert" "trail_tripwire" {
  project_name = alicloud_log_project.sg.project_name
  alert_name   = "audit-trail-tamper-tripwire"
  display_name = "Audit trail tamper tripwire"
  description  = "Fires on ANY StopLogging/DeleteTrail management event (zero tolerance; STRIDE F-05 tripwire, audit-trail-design.md S5)."

  configuration {
    severity_configurations {
      severity = 8
      eval_condition {
        condition = "tripwire >= 1"
      }
    }

    group_configuration {
      type   = "no_group"
      fields = []
    }

    policy_configuration {
      action_policy_id = var.sls_action_policy_id
      repeat_interval  = "5m"
    }

    query_list {
      project        = alicloud_log_project.sg.project_name
      store          = "audit"
      region         = var.region
      store_type     = "log"
      chart_title    = "Trail tamper events"
      query          = "* | select count(*) as tripwire from log where json_extract_scalar(event, '$.eventName') = 'StopLogging' or json_extract_scalar(event, '$.eventName') = 'DeleteTrail'"
      start          = "-300"
      end            = "0"
      time_span_type = "Relative"
    }
  }

  schedule {
    type           = "FixedRate"
    interval       = "5m"
    time_zone      = "Asia/Singapore"
    run_immdiately = true
  }
}
