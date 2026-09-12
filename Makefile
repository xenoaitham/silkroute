# SILKROUTE — task runner. Sim-first per ADR-0001; cloud targets are
# validated-plans mode per ADR-0002 (no AliCloud account: plans prove the HCL,
# nothing is ever applied; deploy targets are guarded).
SHELL := /bin/bash

.PHONY: help up down ps logs restart smoke clean

help: ## list targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

up: ## start the sim infrastructure network (waits until healthy)
	@command -v jq >/dev/null || { echo "ERROR: jq is required by 'make up' (health-wait loop). Install jq first."; exit 1; }
	docker compose up -d
	@echo "waiting for services to become healthy..."
	@timeout 180 bash -c 'until [ "$$(docker compose ps --format json | jq -s "[.[] | select(.Health==\"healthy\" or (.Health==\"\" and .State==\"running\"))] | length")" -eq "$$(docker compose config --services | wc -l)" ]; do sleep 5; done' \
		&& docker compose ps

down: ## stop sim stack and remove volumes (clean state)
	docker compose down -v --remove-orphans

ps: ## show sim stack status
	docker compose ps

logs: ## tail all sim logs
	docker compose logs -f --tail=50

restart: ## down -v then up (full reproducibility check)
	$(MAKE) down
	$(MAKE) up

smoke: ## assert connectivity to ALL five sim services (exit non-zero on any failure)
	@bash scripts/smoke.sh

clean: ## remove stray compose artifacts
	docker compose down -v --remove-orphans --rmi local 2>/dev/null || true

# --- Phase 2: ESB proof layer (tests/esb-int, tests/load, tests/chaos) -------
ESB_ERP_JAR := apps/legacy-erp/target/legacy-erp-1.0.0-SNAPSHOT.jar
ESB_APP_JAR := apps/esb/target/esb-1.0.0-SNAPSHOT.jar
K6_BIN ?= $(HOME)/tools/k6/k6
ESB_ERP_PID := /tmp/silkroute-esb-erp.pid
ESB_APP_PID := /tmp/silkroute-esb-app.pid

.PHONY: esb-int esb-run esb-stop load-orders

esb-int: ## build legacy-erp + esb jars, then run the esb-int suite (it boots both apps itself)
	./mvnw -B -f apps/legacy-erp/pom.xml package -DskipTests
	./mvnw -B -f apps/esb/pom.xml package -DskipTests
	./mvnw -B -f tests/esb-int/pom.xml verify

esb-run: ## convenience: ensure toxiproxy erp proxy, boot ERP(18080) + ESB(18081) with PID files under /tmp, print healths
	@command -v jq >/dev/null || { echo "ERROR: jq is required by 'make esb-run'."; exit 1; }
	@test -f $(ESB_ERP_JAR) || { echo "ERROR: missing $(ESB_ERP_JAR) — run: ./mvnw -B -f apps/legacy-erp/pom.xml package -DskipTests"; exit 1; }
	@test -f $(ESB_APP_JAR) || { echo "ERROR: missing $(ESB_APP_JAR) — run: ./mvnw -B -f apps/esb/pom.xml package -DskipTests"; exit 1; }
	@if [ -f scripts/esb-toxiproxy.sh ]; then bash scripts/esb-toxiproxy.sh; \
	else curl -sf http://127.0.0.1:18474/version >/dev/null || { echo "ERROR: esb toxiproxy not on 18474 — run: make up"; exit 1; }; \
		curl -sf -o /dev/null http://127.0.0.1:18474/proxies/erp || \
		curl -s -X POST http://127.0.0.1:18474/proxies -H 'Content-Type: application/json' \
			-d '{"name":"erp","listen":"127.0.0.1:18180","upstream":"127.0.0.1:18080","enabled":true}' >/dev/null; fi
	@bash tests/chaos/esb-faults.sh reset || true
	SERVER_PORT=18080 ERP_DEMO_GENERATE_ORDERS=0 nohup java -jar $(ESB_ERP_JAR) > /tmp/silkroute-esb-erp.log 2>&1 & echo $$! > $(ESB_ERP_PID)
	ESB_FAULT_INJECTION=false nohup java -jar $(ESB_APP_JAR) > /tmp/silkroute-esb-app.log 2>&1 & echo $$! > $(ESB_APP_PID)
	@echo "waiting for ERP(18080) + ESB(18081) health..."
	@timeout 180 bash -c 'until curl -sf http://127.0.0.1:18080/actuator/health | grep -q UP; do sleep 2; done'
	@timeout 180 bash -c 'until curl -sf http://127.0.0.1:18082/actuator/health | grep -q UP; do sleep 2; done'
	@echo "ERP  health: $$(curl -s http://127.0.0.1:18080/actuator/health)  (pid $$(cat $(ESB_ERP_PID)))"
	@echo "ESB  health: $$(curl -s http://127.0.0.1:18082/actuator/health)  (pid $$(cat $(ESB_APP_PID)))"
	@echo "ESB REST:  http://127.0.0.1:18081/api/v1/orders"
	@echo "PID files: $(ESB_ERP_PID) $(ESB_APP_PID)  (stop with: make esb-stop)"

esb-stop: ## stop the esb-run processes by their recorded PID files only (never pkill)
	@for f in $(ESB_APP_PID) $(ESB_ERP_PID); do \
		if [ -f $$f ]; then pid=$$(cat $$f); \
			echo "esb-stop: killing pid $$pid from $$f"; \
			kill $$pid 2>/dev/null || echo "esb-stop: pid $$pid already gone"; \
			rm -f $$f; else echo "esb-stop: no $$f (nothing to stop)"; fi; done

load-orders: ## run the k6 orders load profile (needs ERP+proxy+ESB from esb-run); records REAL p95 into tests/load/results/
	@test -x $(K6_BIN) || { echo "ERROR: k6 not at $(K6_BIN) (override with K6_BIN=...)"; exit 1; }
	@mkdir -p tests/load/results
	$(K6_BIN) run tests/load/k6-orders.js --summary-export=tests/load/results/k6-orders-summary.json

# --- Phase 4: AliCloud landing zone (validated-plans mode, ADR-0002) ---------
TF_BIN ?= $(HOME)/tools/terraform/terraform
TF_DIR := infra
# deploy/destroy are HARD-GUARDED: they create/destroy real billable resources,
# so they refuse to run unless you set SILKROUTE_CLOUD_CONFIRM=YES AND have
# AliCloud credentials in the environment (ADR-0002: validated-plans until an
# account exists; §8: never leave billable resources running).
CLOUD_GUARD = @if [ "$${SILKROUTE_CLOUD_CONFIRM:-}" != "YES" ] || [ -z "$${ALICLOUD_ACCESS_KEY:-}$${ALICLOUD_ACCESS_KEY_ID:-}" ]; then \
	echo "REFUSING: this target touches a real AliCloud account (billable)."; \
	echo "It requires SILKROUTE_CLOUD_CONFIRM=YES and ALICLOUD_ACCESS_KEY(_ID)/SECRET in the env."; \
	echo "Validated-plans mode (ADR-0002): use 'make plan-sg' — plans create nothing."; exit 2; fi

.PHONY: plan-sg deploy-sg destroy budget-alarm tf-fmt-check residency audit-demo

plan-sg: ## one-command reproducibility proof: init + validate + plan (creates nothing)
	@test -x $(TF_BIN) || { echo "ERROR: terraform not at $(TF_BIN) (override with TF_BIN=...)"; exit 1; }
	cd $(TF_DIR) && $(TF_BIN) init -input=false -no-color \
		&& $(TF_BIN) fmt -check -recursive \
		&& $(TF_BIN) validate -no-color \
		&& $(TF_BIN) plan -input=false -no-color

residency: ## C1 residency checks (static graph + wiring) + selftest negative control
	@bash scripts/residency-tests.sh && bash scripts/residency-tests.sh --selftest

audit-demo: ## audit-query demo: seed sim MySQL audit_event, run the "who did what" cookbook (sim-mode, honest label)
	@bash scripts/audit-demo.sh

tf-fmt-check: ## terraform fmt check only (fast CI-style gate)
	cd $(TF_DIR) && $(TF_BIN) fmt -check -recursive

deploy-sg: ## GUARDED: terraform apply of the SG hub (real billable resources)
	$(CLOUD_GUARD)
	cd $(TF_DIR) && $(TF_BIN) init -input=false -no-color && $(TF_BIN) apply -input=false -no-color -var enable_cn_region=false

destroy: ## GUARDED: terraform destroy of whatever the account holds (§8 hygiene)
	$(CLOUD_GUARD)
	cd $(TF_DIR) && $(TF_BIN) destroy -input=false -no-color

budget-alarm: ## budget alarm (~$20): dry-run by default; `make budget-alarm LIVE=1` + creds to execute
	@bash scripts/budget-alarm.sh
