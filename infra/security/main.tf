# --- KMS -----------------------------------------------------------------
# One key per storage family so OSS and RDS key policies can be audited and
# rotated independently. Keys stay in ap-southeast-1; the CN partition gets its
# own keys (C1: no cross-region key sharing, no cross-region replication).

# pending_window_in_days is the modern replacement for the deprecated
# deletion_window_in_days; is_enabled/key_state are deprecated, keys are
# enabled by default.
resource "alicloud_kms_key" "oss" {
  description            = "SSE master key for silkroute-sg-* OSS buckets (lake + artifacts). Not shared with the CN partition."
  pending_window_in_days = var.kms_pending_window_days
  key_usage              = "ENCRYPT/DECRYPT"
  tags                   = var.common_tags
}

resource "alicloud_kms_key" "rds" {
  description            = "TDE master key for silkroute-sg RDS MySQL instances. Not shared with the CN partition."
  pending_window_in_days = var.kms_pending_window_days
  key_usage              = "ENCRYPT/DECRYPT"
  tags                   = var.common_tags
}

# AliCloud KMS aliases must carry the literal "alias/" prefix.
resource "alicloud_kms_alias" "oss" {
  alias_name = "alias/silkroute-sg-oss"
  key_id     = alicloud_kms_key.oss.id
}

resource "alicloud_kms_alias" "rds" {
  alias_name = "alias/silkroute-sg-rds"
  key_id     = alicloud_kms_key.rds.id
}

# --- RAM: ESB runtime identity -------------------------------------------
# SAE apps assume this role at runtime. Policy is least-privilege: explicit
# action lists only (no wildcard actions anywhere), resources pinned to the
# exact named buckets/logstores because landing-zone names are static.

resource "alicloud_ram_policy" "esb_runtime" {
  policy_name = "silkroute-esb-runtime"
  description = "Runtime permissions for the ESB on SAE: named OSS buckets, KMS decrypt on the two SG keys, SLS log writes to the silkroute-sg project."

  policy_document = jsonencode({
    Version = "1"
    Statement = [
      {
        # Bucket-level browse only; per-object grants below follow the data
        # module's grant matrix (SEC-4-02): artifacts read-only, bronze
        # read/write, silver/gold never touched by the runtime.
        Sid    = "ListNamedBuckets"
        Effect = "Allow"
        Action = [
          "oss:GetBucketInfo",
          "oss:ListObjects",
        ]
        Resource = [
          "acs:oss:*:*:silkroute-sg-artifacts",
          "acs:oss:*:*:silkroute-sg-bronze",
          "acs:oss:*:*:silkroute-sg-silver",
          "acs:oss:*:*:silkroute-sg-gold",
        ]
      },
      {
        Sid      = "ReadArtifactsObjects"
        Effect   = "Allow"
        Action   = ["oss:GetObject"]
        Resource = ["acs:oss:*:*:silkroute-sg-artifacts/*"]
      },
      {
        Sid    = "ReadWriteBronzeObjects"
        Effect = "Allow"
        Action = [
          "oss:GetObject",
          "oss:PutObject",
          "oss:DeleteObject",
        ]
        Resource = ["acs:oss:*:*:silkroute-sg-bronze/*"]
      },
      {
        # SSE-KMS uploads need GenerateDataKey, not just Decrypt (SEC-4-03);
        # without it every bronze PutObject against the encrypted bucket fails.
        Sid      = "EncryptDecryptWithLandingZoneKeys"
        Effect   = "Allow"
        Action   = ["kms:Decrypt", "kms:GenerateDataKey"]
        Resource = [alicloud_kms_key.oss.arn, alicloud_kms_key.rds.arn]
      },
      {
        Sid    = "WriteAppLogs"
        Effect = "Allow"
        Action = [
          "log:PutLogs",
          "log:PostLogStoreLogs",
        ]
        Resource = [
          "acs:log:${var.region}:*:project/silkroute-sg",
          "acs:log:${var.region}:*:project/silkroute-sg/logstore/esb-app",
          "acs:log:${var.region}:*:project/silkroute-sg/logstore/orders-events",
        ]
      },
    ]
  })
}

resource "alicloud_ram_role" "esb_runtime" {
  role_name   = "silkroute-esb-runtime"
  description = "Assumed by SAE when running silkroute-esb; trust is the specific SAE service principal, never *."
  assume_role_policy_document = jsonencode({
    Version = "1"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = ["sae.aliyuncs.com"]
        }
      },
    ]
  })
}

resource "alicloud_ram_role_policy_attachment" "esb_runtime" {
  role_name   = alicloud_ram_role.esb_runtime.role_name
  policy_name = alicloud_ram_policy.esb_runtime.policy_name
  policy_type = alicloud_ram_policy.esb_runtime.type
}

# --- RAM: CI deploy identity ---------------------------------------------
# silkroute-ci is the pipeline principal. Access keys are deliberately NOT in
# IaC: they are created/rotated out-of-band and must never sit in Terraform
# state. The user itself holds no permissions; the pipeline acts only by
# assuming silkroute-ci-deploy.

resource "alicloud_ram_user" "ci" {
  name         = "silkroute-ci"
  display_name = "SilkRoute CI pipeline"
  comments     = "No access keys managed in IaC; keys are created and rotated out-of-band so no secret material reaches state."
}

# RAM requires the CALLER to hold sts:AssumeRole — the role's trust policy
# alone never authorizes it (SEC-4-04). This is the user's ONLY grant: assume
# the deploy role, nothing else.
resource "alicloud_ram_policy" "ci_assume" {
  policy_name = "silkroute-ci-assume"
  description = "Lets the CI user assume silkroute-ci-deploy and nothing else."

  policy_document = jsonencode({
    Version = "1"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["sts:AssumeRole"]
        Resource = ["acs:ram::${var.account_id}:role/silkroute-ci-deploy"]
      },
    ]
  })
}

resource "alicloud_ram_user_policy_attachment" "ci_assume" {
  user_name   = alicloud_ram_user.ci.name
  policy_name = alicloud_ram_policy.ci_assume.policy_name
  policy_type = alicloud_ram_policy.ci_assume.type
}

resource "alicloud_ram_role" "ci_deploy" {
  role_name   = "silkroute-ci-deploy"
  description = "Deployment role assumed by silkroute-ci; trust pins the exact user ARN."
  assume_role_policy_document = jsonencode({
    Version = "1"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          RAM = ["acs:ram::${var.account_id}:user/silkroute-ci"]
        }
      },
    ]
  })
}

# Deploy policy: explicit action lists per landing-zone resource type; ARNs are
# region/account-pinned. Trailing /resource-id/* patterns remain only where the
# resource ID is generated by the platform at create time (no bare "*", no
# wildcard actions, no "service:*").
resource "alicloud_ram_policy" "ci_deploy" {
  policy_name = "silkroute-ci-deploy"
  description = "CI deployment permissions over the landing-zone resource types (network, RAM, KMS, OSS, RDS, SAE, SLS, CMS, Kafka, ActionTrail), scoped to region/account and explicit actions."

  policy_document = jsonencode({
    Version = "1"
    Statement = [
      {
        Sid    = "Network"
        Effect = "Allow"
        Action = [
          "vpc:CreateVpc", "vpc:DeleteVpc", "vpc:DescribeVpcs", "vpc:ModifyVpcAttribute",
          "vpc:CreateVSwitch", "vpc:DeleteVSwitch", "vpc:DescribeVSwitches",
          "vpc:CreateNatGateway", "vpc:DeleteNatGateway", "vpc:DescribeNatGateways",
          "vpc:AllocateEipAddress", "vpc:ReleaseEipAddress", "vpc:DescribeEipAddresses",
          "vpc:AssociateEipAddress", "vpc:UnassociateEipAddress",
        ]
        Resource = [
          "acs:vpc:${var.region}:${var.account_id}:vpc/*",
          "acs:vpc:${var.region}:${var.account_id}:vswitch/*",
          "acs:vpc:${var.region}:${var.account_id}:natgateway/*",
          "acs:vpc:${var.region}:${var.account_id}:eip/*",
        ]
      },
      {
        Sid    = "SecurityGroups"
        Effect = "Allow"
        Action = [
          "ecs:CreateSecurityGroup", "ecs:DeleteSecurityGroup", "ecs:DescribeSecurityGroups",
          "ecs:AuthorizeSecurityGroup", "ecs:AuthorizeSecurityGroupEgress",
          "ecs:RevokeSecurityGroup", "ecs:RevokeSecurityGroupEgress",
        ]
        Resource = ["acs:ecs:${var.region}:${var.account_id}:securitygroup/*"]
      },
      {
        Sid    = "RamGovernance"
        Effect = "Allow"
        Action = [
          "ram:CreatePolicy", "ram:DeletePolicy", "ram:GetPolicy", "ram:ListPolicies", "ram:CreatePolicyVersion", "ram:DeletePolicyVersion",
          "ram:CreateRole", "ram:DeleteRole", "ram:GetRole",
          "ram:CreateUser", "ram:DeleteUser", "ram:GetUser",
          "ram:AttachPolicyToRole", "ram:DetachPolicyFromRole",
          "ram:AttachPolicyToUser", "ram:DetachPolicyFromUser",
        ]
        Resource = [
          "acs:ram:*:${var.account_id}:policy/silkroute-*",
          "acs:ram:*:${var.account_id}:role/silkroute-*",
          "acs:ram:*:${var.account_id}:user/silkroute-*",
        ]
      },
      {
        Sid    = "KmsKeys"
        Effect = "Allow"
        Action = [
          "kms:CreateKey", "kms:DescribeKey", "kms:ScheduleKeyDeletion", "kms:CancelKeyDeletion",
          "kms:CreateAlias", "kms:DescribeAlias", "kms:DeleteAlias",
        ]
        Resource = [
          "acs:kms:${var.region}:${var.account_id}:key/*",
          "acs:kms:${var.region}:${var.account_id}:alias/silkroute-*",
        ]
      },
      {
        Sid    = "OssBuckets"
        Effect = "Allow"
        Action = [
          "oss:PutBucket", "oss:DeleteBucket", "oss:GetBucketInfo", "oss:ListObjects",
          "oss:PutBucketVersioning", "oss:GetBucketVersioning",
          "oss:PutBucketEncryption", "oss:GetBucketEncryption",
          "oss:PutBucketPolicy", "oss:GetBucketPolicy", "oss:DeleteBucketPolicy",
          "oss:PutPublicAccessBlock", "oss:GetPublicAccessBlock",
        ]
        Resource = [
          "acs:oss:*:*:silkroute-sg-*",
          "acs:oss:*:*:silkroute-sg-*/*",
          "acs:oss:*:*:silkroute-cn-*",
          "acs:oss:*:*:silkroute-cn-*/*",
        ]
      },
      {
        Sid    = "RdsInstances"
        Effect = "Allow"
        Action = [
          "rds:CreateDBInstance", "rds:DeleteDBInstance", "rds:DescribeDBInstances",
          "rds:CreateDatabase", "rds:DeleteDatabase", "rds:DescribeDatabases",
          "rds:CreateAccount", "rds:DeleteAccount", "rds:DescribeAccounts",
          "rds:GrantAccountPrivilege", "rds:RevokeAccountPrivilege",
          "rds:ModifySecurityIps", "rds:DescribeSecurityIps",
        ]
        Resource = ["acs:rds:${var.region}:${var.account_id}:dbinstance/*"]
      },
      {
        Sid    = "SaeApplications"
        Effect = "Allow"
        Action = [
          "sae:CreateNamespace", "sae:DeleteNamespace", "sae:DescribeNamespace",
          "sae:CreateApplication", "sae:DeleteApplication", "sae:DescribeApplicationStatus",
          "sae:RestartApplication", "sae:DeployApplication",
        ]
        Resource = ["acs:sae:${var.region}:${var.account_id}:application/*"]
      },
      {
        Sid    = "LogService"
        Effect = "Allow"
        Action = [
          "log:CreateProject", "log:DeleteProject", "log:GetProject",
          "log:CreateLogStore", "log:DeleteLogStore", "log:GetLogStore",
          "log:CreateDashboard", "log:DeleteDashboard",
          "log:CreateAlert", "log:DeleteAlert",
        ]
        Resource = [
          "acs:log:${var.region}:*:project/silkroute-sg",
          "acs:log:${var.region}:*:project/silkroute-sg/*",
        ]
      },
      {
        Sid    = "CloudMonitor"
        Effect = "Allow"
        Action = [
          "cms:PutContactGroup", "cms:DeleteContactGroup", "cms:DescribeContactGroupList",
          "cms:PutResourceMetricRule", "cms:DeleteMetricRules", "cms:ListMetricRules",
        ]
        Resource = ["acs:cms:*:${var.account_id}:alarm/*"]
      },
      {
        Sid    = "Kafka"
        Effect = "Allow"
        Action = [
          "alikafka:CreateInstance", "alikafka:DeleteInstance", "alikafka:ListInstances",
          "alikafka:CreateTopic", "alikafka:DeleteTopic", "alikafka:ListTopic",
          "alikafka:CreateSaslUser", "alikafka:DeleteSaslUser",
        ]
        Resource = ["acs:alikafka:*:${var.account_id}:*instances/*"]
      },
      {
        Sid    = "ActionTrail"
        Effect = "Allow"
        Action = [
          "actiontrail:CreateTrail", "actiontrail:DeleteTrail", "actiontrail:DescribeTrails",
          "actiontrail:StartLogging", "actiontrail:StopLogging",
        ]
        Resource = ["acs:actiontrail:${var.region}:${var.account_id}:trail/*"]
      },
    ]
  })
}

resource "alicloud_ram_role_policy_attachment" "ci_deploy" {
  role_name   = alicloud_ram_role.ci_deploy.role_name
  policy_name = alicloud_ram_policy.ci_deploy.policy_name
  policy_type = alicloud_ram_policy.ci_deploy.type
}
