# ADR-0005: this module is DESIGNED and VALIDATED, never applied from this
# environment. Applying requires a CN-registered AliCloud account with
# real-name verification (and an ICP filing for any public endpoint, C2).

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    alicloud = {
      source  = "aliyun/alicloud"
      version = "1.285.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }
}
