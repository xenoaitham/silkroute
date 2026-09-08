# SILKROUTE — task runner. Sim-first per ADR-0001; cloud targets land with Phase 4 (see ADR-0002).
SHELL := /bin/bash

.PHONY: help up down ps logs restart smoke clean

help: ## list targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

up: ## start the sim infrastructure network (waits until healthy)
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

smoke: ## quick connectivity smoke test against sim services
	@docker compose exec -T redis redis-cli ping
	@docker compose exec -T mysql mysqladmin ping -h 127.0.0.1 -psilkroute 2>/dev/null
	@echo "smoke OK"

clean: ## remove stray compose artifacts
	docker compose down -v --remove-orphans --rmi local 2>/dev/null || true
