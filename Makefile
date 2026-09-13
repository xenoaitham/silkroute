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
	$(java17_env) SERVER_PORT=18080 ERP_DEMO_GENERATE_ORDERS=0 nohup java -jar $(ESB_ERP_JAR) > /tmp/silkroute-esb-erp.log 2>&1 & echo $$! > $(ESB_ERP_PID)
	$(java17_env) ESB_FAULT_INJECTION=false nohup java -jar $(ESB_APP_JAR) > /tmp/silkroute-esb-app.log 2>&1 & echo $$! > $(ESB_APP_PID)
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

# --- Phase 3: ETL data plane (apps/modern-oms, apps/cdc, apps/batch) ---------
# Same PID-file/no-pkill hygiene as the esb-run/esb-stop pair above. Every
# process runs as a host JVM with ZERO new listening ports. Knobs are env
# vars with ${VAR:default} defaults inside the apps (see apps/*/README.md).
# JAVA17 is preferred when present: the poms compile with release 17 and the
# Spark 3.5.1 runtime officially targets JDK 17.
OMS_JAR := apps/modern-oms/target/oms-1.0.0-SNAPSHOT.jar
CDC_JAR := apps/cdc/target/cdc-1.0.0-SNAPSHOT.jar
CDC_BRONZE_JAR := apps/cdc/target/cdc-1.0.0-SNAPSHOT-bronze.jar
BATCH_JAR := apps/batch/target/batch-1.0.0-SNAPSHOT.jar
OMS_PID := /tmp/silkroute-oms.pid
CDC_ENGINE_PID := /tmp/silkroute-cdc-engine.pid
CDC_BRONZE_PID := /tmp/silkroute-cdc-bronze.pid
OMS_LOG := /tmp/silkroute-oms.log
CDC_ENGINE_LOG := /tmp/silkroute-cdc-engine.log
CDC_BRONZE_LOG := /tmp/silkroute-cdc-bronze.log
JAVA17_DIR := /usr/lib/jvm/java-17-openjdk-amd64

define java17_env
	if [ -d "$(JAVA17_DIR)" ]; then export JAVA_HOME="$(JAVA17_DIR)"; export PATH="$$JAVA_HOME/bin:$$PATH"; fi;
endef

.PHONY: etl-setup etl-reset oms-run oms-build oms-stop cdc-run cdc-build cdc-stop seed-day batch-run etl-check
# no-op argument words so `make batch-run full|recon-only|selftest` parses;
# the work happens inside batch-run
.PHONY: full recon-only selftest
full recon-only selftest:
	@:

etl-setup: ## idempotent data-plane bootstrap: DBs, users, lake tables, cdc topic, lake buckets (scripts/etl-setup.sh)
	@bash scripts/etl-setup.sh

etl-reset: ## clean-slate the ETL world (stops oms/cdc/esb, truncates oms/lake tables, purges topics/groups/buckets/offsets) — scripts/etl-reset.sh
	@bash scripts/etl-reset.sh

oms-run: ## boot the OMS event store (builds if needed); waits for OMS-CONSUMER-START; PID $(OMS_PID) log $(OMS_LOG)
	@if [ -f $(OMS_PID) ] && kill -0 "$$(cat $(OMS_PID))" 2>/dev/null; then echo "oms-run: already running (pid $$(cat $(OMS_PID)))"; exit 0; fi
	@test -f $(OMS_JAR) || $(MAKE) --no-print-directory oms-build
	$(java17_env) \
		nohup java -jar $(OMS_JAR) > $(OMS_LOG) 2>&1 & echo $$! > $(OMS_PID)
	@echo "waiting for the OMS consumer to subscribe (timeout 180s)..."
	@timeout 180 bash -c 'until grep -q OMS-CONSUMER-START $(OMS_LOG) 2>/dev/null; do \
		kill -0 "$$(cat $(OMS_PID))" 2>/dev/null || { echo "ERROR: OMS process died — see $(OMS_LOG)"; tail -20 $(OMS_LOG); exit 1; }; sleep 2; done' \
		|| { echo "ERROR: OMS did not reach OMS-CONSUMER-START within 180s — see $(OMS_LOG)"; exit 1; }
	@echo "OMS running: pid $$(cat $(OMS_PID)) log $(OMS_LOG) (stop with: make oms-stop)"

oms-build: ## build apps/modern-oms (jar + unit tests skipped here; run ./mvnw package for tests)
	$(java17_env) ./mvnw -B -f apps/modern-oms/pom.xml package -DskipTests

oms-stop: ## stop the OMS by its recorded PID file only (never pkill)
	@if [ -f $(OMS_PID) ]; then pid=$$(cat $(OMS_PID)); \
		echo "oms-stop: killing pid $$pid from $(OMS_PID)"; \
		kill $$pid 2>/dev/null || echo "oms-stop: pid $$pid already gone"; \
		rm -f $(OMS_PID); else echo "oms-stop: no $(OMS_PID) (nothing to stop)"; fi

cdc-run: ## boot BOTH cdc mains: engine (binlog->topic) + bronze writer (topic->MinIO); waits for CDC-ENGINE-START
	@if [ -f $(CDC_ENGINE_PID) ] && kill -0 "$$(cat $(CDC_ENGINE_PID))" 2>/dev/null; then echo "cdc-run: engine already running (pid $$(cat $(CDC_ENGINE_PID)))"; exit 0; fi
	@test -f $(CDC_JAR) || $(MAKE) --no-print-directory cdc-build
	$(java17_env) \
		nohup java -jar $(CDC_JAR) > $(CDC_ENGINE_LOG) 2>&1 & echo $$! > $(CDC_ENGINE_PID)
	$(java17_env) \
		nohup java -jar $(CDC_BRONZE_JAR) > $(CDC_BRONZE_LOG) 2>&1 & echo $$! > $(CDC_BRONZE_PID)
	@echo "waiting for the CDC engine to start capturing (timeout 180s)..."
	@timeout 180 bash -c 'until grep -qE "CDC-ENGINE-START|CDC-CAPTURE" $(CDC_ENGINE_LOG) 2>/dev/null; do \
		kill -0 "$$(cat $(CDC_ENGINE_PID))" 2>/dev/null || { echo "ERROR: CDC engine died — see $(CDC_ENGINE_LOG)"; tail -30 $(CDC_ENGINE_LOG); exit 1; }; sleep 2; done' \
		|| { echo "ERROR: CDC engine did not start within 180s — see $(CDC_ENGINE_LOG)"; exit 1; }
	@sleep 5
	@if ! kill -0 "$$(cat $(CDC_ENGINE_PID))" 2>/dev/null; then echo "ERROR: CDC engine died right after start (snapshot/binlog failure?) — see $(CDC_ENGINE_LOG)"; tail -30 $(CDC_ENGINE_LOG); exit 1; fi
	@if ! kill -0 "$$(cat $(CDC_BRONZE_PID))" 2>/dev/null; then echo "ERROR: bronze writer died right after start — see $(CDC_BRONZE_LOG)"; tail -30 $(CDC_BRONZE_LOG); exit 1; fi
	@echo "CDC engine running: pid $$(cat $(CDC_ENGINE_PID)) log $(CDC_ENGINE_LOG)"
	@echo "Bronze writer running: pid $$(cat $(CDC_BRONZE_PID)) log $(CDC_BRONZE_LOG) (stop with: make cdc-stop)"

cdc-build: ## build apps/cdc (both shaded jars)
	$(java17_env) ./mvnw -B -f apps/cdc/pom.xml package -DskipTests

cdc-stop: ## stop engine + bronze writer by their recorded PID files only (never pkill)
	@for f in $(CDC_BRONZE_PID) $(CDC_ENGINE_PID); do \
		if [ -f $$f ]; then pid=$$(cat $$f); \
			echo "cdc-stop: killing pid $$pid from $$f"; \
			kill $$pid 2>/dev/null || echo "cdc-stop: pid $$pid already gone"; \
			rm -f $$f; else echo "cdc-stop: no $$f (nothing to stop)"; fi; done

seed-day: ## drive N live orders through the ESB: make seed-day N=12 (default 30) — no synthetic SQL
	@bash scripts/seed-day.sh $(if $(N),$(N),30)

batch-run: ## Spark batch: make batch-run ARGS="full --business-date 2026-09-13" | ARGS="recon-only" | ARGS="selftest" (default full)
	$(java17_env) \
		if [ ! -f $(BATCH_JAR) ]; then ./mvnw -B -f apps/batch/pom.xml package -DskipTests; fi
	$(java17_env) \
		java -jar $(BATCH_JAR) $(if $(ARGS),$(ARGS),$(or $(firstword $(filter-out batch-run,$(MAKECMDGOALS))),full))

etl-check: ## cheap postcondition ritual: oms rows exist, bronze objects exist, recon report allMatch
	@echo "ETL-CHECK oms rows (silkroute_oms.oms_order / oms_order_line):"
	@docker exec sim-mysql mysql -uroot -p"$${MYSQL_ROOT_PASSWORD:-silkroute}" -N -B \
		-e "SELECT 'orders=', COUNT(*) FROM silkroute_oms.oms_order UNION ALL SELECT 'lines=', COUNT(*) FROM silkroute_oms.oms_order_line;" 2>/dev/null \
		|| { echo "ETL-CHECK FAIL: cannot query silkroute_oms — run make etl-setup + oms-run first"; exit 1; }
	@echo "ETL-CHECK bronze objects (per bucket prefix):"
	@docker exec sim-minio mc find local/silkroute-sg-bronze --name '*.jsonl' 2>/dev/null | head -5
	@n=$$(docker exec sim-minio mc find local/silkroute-sg-bronze --name '*.jsonl' 2>/dev/null | wc -l); \
		[ "$$n" -gt 0 ] || { echo "ETL-CHECK FAIL: no bronze objects landed — run make cdc-run + seed-day first"; exit 1; }; \
		echo "ETL-CHECK PASS: $$n bronze object(s)"
	@if [ -f /tmp/silkroute-recon-report.json ]; then \
		jq -e '.allMatch == true' /tmp/silkroute-recon-report.json >/dev/null \
			&& echo "ETL-CHECK PASS: recon report allMatch=true" \
			|| { echo "ETL-CHECK FAIL: recon report allMatch!=true — see /tmp/silkroute-recon-report.json"; exit 1; }; \
	else echo "ETL-CHECK WARN: no recon report at /tmp/silkroute-recon-report.json yet — run make batch-run ARGS=full"; fi
	@echo "ETL-CHECK PASS: data plane postconditions hold"
