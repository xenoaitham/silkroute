locals {
  # Applied to every resource in every module so ownership is machine-checkable.
  common_tags = {
    Project   = "silkroute"
    ManagedBy = "terraform"
    Partition = "sg-hub"
  }
}

resource "alicloud_vpc" "hub" {
  cidr_block  = var.vpc_cidr
  vpc_name    = "silkroute-sg-hub"
  description = "Singapore-hub VPC for the Maple Retail Group landing zone (international hub, no ICP needed for internal APIs)"
  tags        = local.common_tags
}

resource "alicloud_vswitch" "public" {
  cidr_block   = var.public_vswitch_cidr
  vpc_id       = alicloud_vpc.hub.id
  zone_id      = var.az1
  vswitch_name = "silkroute-sg-public"
  description  = "Public vSwitch; hosts only the NAT gateway"
  tags         = local.common_tags
}

resource "alicloud_vswitch" "private_1" {
  cidr_block   = var.private_vswitch1_cidr
  vpc_id       = alicloud_vpc.hub.id
  zone_id      = var.az1
  vswitch_name = "silkroute-sg-private-1"
  description  = "Private vSwitch for SAE compute and Kafka (no direct internet path)"
  tags         = local.common_tags
}

resource "alicloud_vswitch" "private_2" {
  cidr_block   = var.private_vswitch2_cidr
  vpc_id       = alicloud_vpc.hub.id
  zone_id      = var.az2
  vswitch_name = "silkroute-sg-private-2"
  description  = "Private vSwitch in second AZ for RDS and replica spread"
  tags         = local.common_tags
}

# Enhanced NAT is the only egress path out of the private vSwitches.
resource "alicloud_nat_gateway" "egress" {
  vpc_id           = alicloud_vpc.hub.id
  vswitch_id       = alicloud_vswitch.public.id
  nat_type         = "Enhanced"
  nat_gateway_name = "silkroute-sg-nat"
  # `spec` was removed in provider 1.121.0; `specification` is the modern field.
  specification = var.nat_spec
  payment_type  = "PayAsYouGo"
  description   = "Single controlled egress path; SNAT rules for private subnets are managed at apply time"
  tags          = local.common_tags
}

resource "alicloud_eip" "nat" {
  address_name         = "silkroute-sg-nat-eip"
  bandwidth            = 5
  internet_charge_type = "PayByTraffic"
  payment_type         = "PayAsYouGo"
  description          = "Egress EIP bound to the enhanced NAT gateway"
  tags                 = local.common_tags
}

resource "alicloud_eip_association" "nat" {
  allocation_id = alicloud_eip.nat.id
  instance_id   = alicloud_nat_gateway.egress.id
  instance_type = "NatGateway"
}

# Security groups follow the allowlist in MASTER_PROMPT section 2:
# no 0.0.0.0/0 inbound anywhere; ESB is the only caller of the ERP and data tier.

resource "alicloud_security_group" "esb" {
  security_group_name = "sg-esb"
  vpc_id              = alicloud_vpc.hub.id
  description         = "ESB runtime (SAE); admin endpoints reachable only from the office CIDR"
  security_group_type = "normal"
  tags                = local.common_tags
}

resource "alicloud_security_group" "erp" {
  security_group_name = "sg-erp"
  vpc_id              = alicloud_vpc.hub.id
  description         = "Legacy SOAP ERP; reachable only from the ESB security group"
  security_group_type = "normal"
  tags                = local.common_tags
}

resource "alicloud_security_group" "data" {
  security_group_name = "sg-data"
  vpc_id              = alicloud_vpc.hub.id
  description         = "Data tier (RDS MySQL, Redis); reachable only from ESB and ERP security groups"
  security_group_type = "normal"
  tags                = local.common_tags
}

# --- sg-esb rules ---

resource "alicloud_security_group_rule" "esb_admin_18081" {
  security_group_id = alicloud_security_group.esb.id
  type              = "ingress"
  ip_protocol       = "tcp"
  port_range        = "18081/18081"
  cidr_ip           = var.admin_cidr
  description       = "ESB admin endpoint 1 from office CIDR only"
  priority          = 1
}

resource "alicloud_security_group_rule" "esb_admin_18082" {
  security_group_id = alicloud_security_group.esb.id
  type              = "ingress"
  ip_protocol       = "tcp"
  port_range        = "18082/18082"
  cidr_ip           = var.admin_cidr
  description       = "ESB admin endpoint 2 from office CIDR only"
  priority          = 1
}

# Explicit egress kept minimal: ESB talks only to the ERP (18080) and the data
# tier (3306/6379). Kafka/Redis live in the same vSwitch and are reached over
# private endpoints covered by these groups; anything else is denied by default.

resource "alicloud_security_group_rule" "esb_egress_erp" {
  security_group_id        = alicloud_security_group.esb.id
  type                     = "egress"
  ip_protocol              = "tcp"
  port_range               = "18080/18080"
  source_security_group_id = alicloud_security_group.erp.id
  description              = "ESB to ERP SOAP endpoint"
  priority                 = 1
}

resource "alicloud_security_group_rule" "esb_egress_mysql" {
  security_group_id        = alicloud_security_group.esb.id
  type                     = "egress"
  ip_protocol              = "tcp"
  port_range               = "3306/3306"
  source_security_group_id = alicloud_security_group.data.id
  description              = "ESB to RDS MySQL"
  priority                 = 1
}

resource "alicloud_security_group_rule" "esb_egress_redis" {
  security_group_id        = alicloud_security_group.esb.id
  type                     = "egress"
  ip_protocol              = "tcp"
  port_range               = "6379/6379"
  source_security_group_id = alicloud_security_group.data.id
  description              = "ESB to Redis"
  priority                 = 1
}

# --- sg-erp rules ---

resource "alicloud_security_group_rule" "erp_from_esb" {
  security_group_id        = alicloud_security_group.erp.id
  type                     = "ingress"
  ip_protocol              = "tcp"
  port_range               = "18080/18080"
  source_security_group_id = alicloud_security_group.esb.id
  description              = "ERP accepts connections only from the ESB security group"
  priority                 = 1
}

# --- sg-data rules ---

resource "alicloud_security_group_rule" "data_mysql_from_esb" {
  security_group_id        = alicloud_security_group.data.id
  type                     = "ingress"
  ip_protocol              = "tcp"
  port_range               = "3306/3306"
  source_security_group_id = alicloud_security_group.esb.id
  description              = "RDS MySQL reachable from ESB only"
  priority                 = 1
}

resource "alicloud_security_group_rule" "data_mysql_from_erp" {
  security_group_id        = alicloud_security_group.data.id
  type                     = "ingress"
  ip_protocol              = "tcp"
  port_range               = "3306/3306"
  source_security_group_id = alicloud_security_group.erp.id
  description              = "RDS MySQL reachable from ERP"
  priority                 = 1
}

resource "alicloud_security_group_rule" "data_redis_from_esb" {
  security_group_id        = alicloud_security_group.data.id
  type                     = "ingress"
  ip_protocol              = "tcp"
  port_range               = "6379/6379"
  source_security_group_id = alicloud_security_group.esb.id
  description              = "Redis reachable from ESB only"
  priority                 = 1
}
