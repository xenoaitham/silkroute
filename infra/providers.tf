# Placeholder credentials exist ONLY so `terraform plan` runs credential-free
# (ADR-0002 validated-plans mode): plans perform no AliCloud API calls. In a
# real account, credentials come from the environment/profile
# (ALICLOUD_ACCESS_KEY / ALICLOUD_SECRET_KEY) and these variables are removed;
# no real key material ever enters this repository.

provider "alicloud" {
  access_key = var.access_key
  secret_key = var.secret_key
  region     = var.region
}

# The CN partition MUST have its own aliased provider: Terraform resource
# placement is provider-level, so without this alias every "CN" resource would
# land in ap-southeast-1 and C1 residency would be tags-only theater
# (SEC-4-01). Wired to the module via the `providers` meta-argument in main.tf.
provider "alicloud" {
  alias      = "cn"
  access_key = var.access_key
  secret_key = var.secret_key
  region     = var.cn_region
}
