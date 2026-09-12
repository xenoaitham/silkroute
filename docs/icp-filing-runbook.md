# ICP filing runbook — mainland-China public web presence (C2)

Operator runbook for the day Maple Retail Group (**fictional**, self-directed reference implementation) puts a public web presence in mainland China. It is written for that future moment, not for today.

## Scope: when a filing is needed and when it is not

The dividing line is where the server sits, per Alibaba Cloud's own filing documentation:

> "ICP filing applies only to servers located in the Chinese mainland" — https://www.alibabacloud.com/help/en/icp-filing/basic-icp-service/product-overview/what-is-an-icp-filing
> Websites/apps "with domain names resolving to servers in the Chinese mainland" must obtain the filing **before providing services**; "China requires ICP filing for non-commercial and ICP licensing for commercial Internet Information Services"; "You cannot serve Internet Information Services without a license or ICP filing." — https://help.aliyun.com/en/icp-filing/basic-icp-service/product-overview/icp-filing-requirements-for-a-regular-website

- **Required**: any website or app served from servers physically in the mainland — including a mainland-hosted storefront, marketing site, or public API, and any domain resolving to such servers.
- **Not required**: anything hosted outside the mainland. Singapore-hosted internal APIs (the C2 hub, `ap-southeast-1`) need no filing — the quote above is why C2's claim holds.
- **This project today**: the CN partition as designed (`infra/cn-partition/`) is **compute-and-data-only** — private VPC, closed-by-default security group, no NAT/EIP, no public endpoints (`infra/cn-partition/main.tf:1-2,29-37`) — so it needs **no filing today**. This runbook exists for the moment a public presence lands. Note the ordering trap: the partition itself requires a **CN-registered account** (real-name verification) before anything applies at all (ADR-0005); the filing work below happens on that account.

**Filing vs. license**: non-commercial content needs the **ICP filing (备案)**; commercial Internet Information Services need the **ICP license (许可证)**. Decide which one the future presence is before starting — it changes the paperwork, not the pipeline below.

## Prerequisites

1. **A CN-registered entity** with a mainland business license. This is the same prerequisite as applying the CN partition itself (ADR-0005); without the entity, nothing else in this runbook can start.
2. **Real-name verification**: of the entity's legal representative, and of the AliCloud account that will submit the filing (corporate accounts verify against the business license).
3. **A domain registered in the entity's own name** (the filing attaches the domain to the license holder). The domain must not serve anything while the filing is in review (see step 5).
4. **The responsible person**: an individual who will complete provider/authority video or live checks (typically the legal representative) with valid mainland ID.

## The steps

1. **Prepare entity + person materials**: business license (unified social credit code), legal representative ID and contact details, the responsible person's details, the domain certificate, and — for the website/app — its name and the service content that decides filing vs. license. Keep names exactly consistent across all documents; mismatch is the classic rejection cause.
2. **Submit through the cloud provider's filing service** — the Alibaba Cloud ICP Filing system on the China site (https://www.alibabacloud.com/help/en/icp-filing/basic-icp-service/user-guide/icp-filing-application-overview). The provider is the access node to the regulator: the filing is *submitted via the host provider*, not directly to MIIT.
3. **Provider pre-review**: the provider checks the materials and completeness first, then passes the filing to the regulator. Fix any rejection here; it is the cheap place to fix it.
4. **Communications-administration (MIIT provincial) review**: the provincial Communications Administration reviews and decides (the process overview's "Communications administration review" stage). The provider's documentation states no fixed duration for this review — **plan the launch gate accordingly**: do not schedule any public launch that depends on a review date you do not control.
5. **Filing number issued → only then may the domain serve.** Until the number issues, the domain **cannot serve** — this is not a soft guideline: per the requirements page above, domains resolving to mainland servers must obtain the filing *before* providing services, and providers enforce it by blocking serving on un-filed domains. Sequence any launch checklist so "filing number issued" is a hard gate in front of "public endpoint live".
6. **Display obligations**: once issued, the filing number must be displayed on the site — Alibaba Cloud's guidance states that once the number is issued "you need to display it in the footer of your website" (https://www.alibabacloud.com/blog/597263), and provider filing notices require displaying the number at the bottom of the site with a link to the MIIT filing-query system (commonly `beian.miit.gov.cn` — confirm the exact link and placement at filing time, as this detail is provider-notice material rather than statute text).
7. **Keep it current**: material changes (entity, domain, service content) go through change-filing; keep registration details up to date with MIIT.

## What this project would need (honest closing)

- A **public endpoint in the CN partition** — which the current design deliberately does not have (no NAT/EIP, closed-by-default `sg-cn-esb`); adding one is a design change requiring an ADR and re-validated plans, not a toggle.
- A **mainland-hosted front** (storefront or site) deployed to that endpoint, served from a domain registered to the CN entity.
- The **filing gate in the launch checklist**: "ICP number issued and displayed" sits before "public traffic enabled", with the runbook steps above as the definition of done. Until then, the honest status is: nothing public exists in the mainland, so nothing needs a filing — and the SG hub carries the international surface, filing-free.
