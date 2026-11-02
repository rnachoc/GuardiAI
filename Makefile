# GuardiAI. Docker and make are the only tools a machine needs (ADR 3): every check below runs
# in a container whose image is pinned by digest. `make` alone lists the targets.
#
# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>

SHELL := /bin/sh
.DEFAULT_GOAL := help

COMPOSE := docker compose
# Run tools as the current user, so nothing they write ends up owned by root.
DOCKER_RUN := docker run --rm --user $$(id -u):$$(id -g) --volume "$(CURDIR)":/repo --workdir /repo

MAVEN_IMAGE := maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320
NODE_IMAGE := node:22.23.4-alpine3.23@sha256:6abbcf5dd6b231da109bcde1181db1d92bf78015ae1a9514d9807c1f857869db
PYTHON_IMAGE := python:3.13.18-alpine3.23@sha256:e4aeaca9081549f19bd5bc8605ed5ab257ba34f97e0471f8e09161f45b4b08b8
ACTIONLINT_IMAGE := rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667
YAMLLINT_IMAGE := pipelinecomponents/yamllint:0.35.27@sha256:f7f7b4f8824839e33690a880dbe20944128b0dc9c55522a0b92a29b3a905cecf
EDITORCONFIG_IMAGE := mstruebing/editorconfig-checker:4.0.2@sha256:2ba6232bfa0058f72f5f7d7816711590aa764afab1542af30b3ecb4553587918
SHELLCHECK_IMAGE := koalaman/shellcheck:v0.11.0@sha256:61862eba1fcf09a484ebcc6feea46f1782532571a34ed51fedf90dd25f925a8d
GITLEAKS_IMAGE := zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f

# Extra flags for the Maven container, for a machine behind a TLS-intercepting proxy, for example
# MAVEN_DOCKER_FLAGS="--network host -e MAVEN_OPTS=... -v /path/to/truststore:/opt/proxy-truststore:ro"
MAVEN_DOCKER_FLAGS ?=
# The same for the Node container, for example NPM_DOCKER_FLAGS="--network host -e HTTPS_PROXY=..."
NPM_DOCKER_FLAGS ?=

# A local gitleaks binary can replace the container: make secrets GITLEAKS=gitleaks
GITLEAKS ?= $(DOCKER_RUN) $(GITLEAKS_IMAGE)

SHELL_SCRIPTS := scripts/smoke.sh scripts/e2e_remediation.sh .githooks/pre-commit

.PHONY: help up down clean ps logs smoke e2e-remediation verify test test-repo test-mercadillo test-policy test-chaos test-mcp test-agents test-evals test-webhook-mock eval console investigate postmortem chaos record record-all chaos-reset dashboard lint secrets secrets-staged hooks

help: ## List the targets.
	@awk 'BEGIN {FS = ":.*## "} /^[a-z-]+:.*## / {printf "  %-15s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

.env:
	cp .env.example .env
	@echo "Created .env from .env.example. Its values are local-only placeholders."

up: .env ## Build and start the stack, and wait until every service is healthy.
	$(COMPOSE) up --build --detach --wait --wait-timeout 600

down: ## Stop the stack and keep its data.
	$(COMPOSE) down

clean: ## Stop the stack and delete its data.
	$(COMPOSE) down --volumes --remove-orphans

ps: ## Show the state of each service.
	$(COMPOSE) ps

logs: ## Follow the logs of every service.
	$(COMPOSE) logs --follow --tail 100

smoke: ## Prove each backing service of the running stack does its job.
	./scripts/smoke.sh

e2e-remediation: ## Kill inventory, then propose, approve, execute and verify its restart on the running stack.
	./scripts/e2e_remediation.sh

verify: ## Prove the running stack meets Phase 1: a purchase, live traffic, a trace across services, logs, the dashboard.
	docker run --rm --network guardia_default --volume "$(CURDIR)/scripts":/scripts:ro \
		--env PYTHONDONTWRITEBYTECODE=1 $(PYTHON_IMAGE) python3 /scripts/verify_stack.py

test: test-repo test-mercadillo test-chaos test-mcp test-agents test-policy test-evals test-webhook-mock ## Run every test suite. Needs no API key and no running stack.

test-repo: ## Test the repository's own rules and tools.
	$(DOCKER_RUN) --env PYTHONDONTWRITEBYTECODE=1 $(PYTHON_IMAGE) python3 -m unittest discover --start-directory tests --verbose

test-mercadillo: ## Compile and test the Mercadillo services.
	mkdir -p .cache/m2
	$(DOCKER_RUN) --env MAVEN_CONFIG=/var/maven/.m2 --volume "$(CURDIR)/.cache/m2":/var/maven/.m2 $(MAVEN_DOCKER_FLAGS) \
		--workdir /repo/mercadillo $(MAVEN_IMAGE) mvn --batch-mode --no-transfer-progress --strict-checksums -Duser.home=/var/maven verify

test-webhook-mock: ## Test the stand-in for a Slack webhook that shows escalations.
	$(DOCKER_RUN) --workdir /repo/deploy/webhook-mock $(NODE_IMAGE) node --test server.test.mjs

test-policy: ## Test the policy engine, whose tests were written first.
	mkdir -p .cache/m2
	$(DOCKER_RUN) --env MAVEN_CONFIG=/var/maven/.m2 --volume "$(CURDIR)/.cache/m2":/var/maven/.m2 $(MAVEN_DOCKER_FLAGS) \
		--workdir /repo/policy $(MAVEN_IMAGE) mvn --batch-mode --no-transfer-progress --strict-checksums -Duser.home=/var/maven verify

test-chaos: ## Type-check guardia-chaos, test it, validate every scenario and verify every recording.
	mkdir -p .cache/npm
	$(DOCKER_RUN) --env npm_config_cache=/repo/.cache/npm --env npm_config_update_notifier=false $(NPM_DOCKER_FLAGS) \
		--workdir /repo/chaos $(NODE_IMAGE) sh -c 'npm ci --no-audit --no-fund && npm run typecheck && npm test && npm run validate && node src/cli.ts verify-fixtures'

test-mcp: ## Type-check and test the tool servers, against the recorded bundles.
	mkdir -p .cache/npm
	$(DOCKER_RUN) --env npm_config_cache=/repo/.cache/npm --env npm_config_update_notifier=false $(NPM_DOCKER_FLAGS) \
		--workdir /repo/mcp $(NODE_IMAGE) sh -c 'npm ci --no-audit --no-fund && npm run typecheck && npm test'

test-agents: ## Type-check and test the investigator, against a recorded bundle and scripted turns.
	mkdir -p .cache/npm
	$(DOCKER_RUN) --env npm_config_cache=/repo/.cache/npm --env npm_config_update_notifier=false $(NPM_DOCKER_FLAGS) \
		--workdir /repo $(NODE_IMAGE) sh -c 'cd mcp && npm ci --no-audit --no-fund && cd ../agents && npm ci --no-audit --no-fund && npm run typecheck && npm test'

test-evals: ## Test the graders, and fail when evals/REPORT.md is stale or a threshold is crossed.
	mkdir -p .cache/npm
	$(DOCKER_RUN) --env npm_config_cache=/repo/.cache/npm --env npm_config_update_notifier=false $(NPM_DOCKER_FLAGS) \
		--workdir /repo $(NODE_IMAGE) sh -c 'for p in chaos mcp agents evals; do (cd $$p && npm ci --no-audit --no-fund) || exit 1; done; cd evals && npm run typecheck && npm test && node src/cli.ts --check'

eval: ## Run the evaluation over every recorded scenario and regenerate evals/REPORT.md.
	$(DOCKER_RUN) --workdir /repo/evals $(NODE_IMAGE) node src/cli.ts

console: ## Build the replay console into console/dist/index.html, from the recordings and scripted turns.
	mkdir -p .cache/npm
	$(DOCKER_RUN) --env npm_config_cache=/repo/.cache/npm --env npm_config_update_notifier=false $(NPM_DOCKER_FLAGS) \
		--workdir /repo $(NODE_IMAGE) sh -c 'for p in chaos mcp agents evals; do test -d $$p/node_modules || (cd $$p && npm ci --no-audit --no-fund) || exit 1; done; node console/src/build.ts'

postmortem: ## Print the postmortem of one scenario in replay. Costs nothing: make postmortem SCENARIO=payments-latency
	@test -n "$(SCENARIO)" || (echo "name a scenario: make postmortem SCENARIO=<id>" >&2; exit 1)
	test -d agents/node_modules || $(MAKE) test-agents
	@$(DOCKER_RUN) $(NPM_DOCKER_FLAGS) --workdir /repo/agents $(NODE_IMAGE) node src/postmortem-cli.ts $(SCENARIO)

investigate: ## Investigate a recorded scenario. Free by default; GUARDIA_MODEL_PROVIDER=anthropic spends, under GUARDIA_MAX_SPEND_USD.
	@test -n "$(SCENARIO)" || (echo "name a scenario: make investigate SCENARIO=<id>" >&2; exit 1)
	test -d agents/node_modules || $(MAKE) test-agents
	$(DOCKER_RUN) --env GUARDIA_MODEL_PROVIDER --env GUARDIA_MAX_SPEND_USD --env ANTHROPIC_API_KEY $(NPM_DOCKER_FLAGS) \
		--workdir /repo/agents $(NODE_IMAGE) node src/cli.ts $(SCENARIO)

chaos: ## Run one scenario against the running stack: make chaos SCENARIO=payments-latency
	@test -n "$(SCENARIO)" || (echo "name a scenario: make chaos SCENARIO=<id>" >&2; exit 1)
	test -d chaos/node_modules || $(MAKE) test-chaos
	docker run --rm --init --network guardia_default --volume "$(CURDIR)/chaos":/chaos \
		--volume /var/run/docker.sock:/var/run/docker.sock --workdir /chaos $(NODE_IMAGE) node src/cli.ts run $(SCENARIO)

record: ## Run one scenario and record it as a fixture bundle: make record SCENARIO=payments-latency
	@test -n "$(SCENARIO)" || (echo "name a scenario: make record SCENARIO=<id>" >&2; exit 1)
	test -d chaos/node_modules || $(MAKE) test-chaos
	docker run --rm --init --network guardia_default --volume "$(CURDIR)/chaos":/chaos \
		--volume /var/run/docker.sock:/var/run/docker.sock --workdir /chaos --env HOST_IDS=$$(id -u):$$(id -g) $(NODE_IMAGE) \
		sh -c 'node src/cli.ts record $(SCENARIO); status=$$?; chown -R "$$HOST_IDS" fixtures runs 2>/dev/null; exit $$status'

record-all: ## Record every scenario, one after another. Takes about an hour.
	for scenario in $$(ls chaos/scenarios | sed 's/\.yaml$$//'); do $(MAKE) --no-print-directory record SCENARIO=$$scenario || exit 1; done

chaos-reset: ## Remove every network fault guardia-chaos may have left behind.
	docker run --rm --network guardia_default --volume "$(CURDIR)/chaos":/chaos --workdir /chaos $(NODE_IMAGE) node src/cli.ts reset

dashboard: ## Regenerate the Grafana dashboard from scripts/build_dashboard.py.
	$(DOCKER_RUN) --env PYTHONDONTWRITEBYTECODE=1 $(PYTHON_IMAGE) python3 scripts/build_dashboard.py

lint: ## Check workflows, YAML, editor rules, shell scripts, authorship and the Compose file.
	$(DOCKER_RUN) $(ACTIONLINT_IMAGE) -color
	$(DOCKER_RUN) $(YAMLLINT_IMAGE) yamllint --strict .
	$(DOCKER_RUN) $(EDITORCONFIG_IMAGE) editorconfig-checker
	$(DOCKER_RUN) $(SHELLCHECK_IMAGE) $(SHELL_SCRIPTS)
	$(DOCKER_RUN) --env PYTHONDONTWRITEBYTECODE=1 $(PYTHON_IMAGE) python3 scripts/check_authorship.py
	$(COMPOSE) --env-file .env.example config --quiet

secrets: ## Scan the whole git history for secrets.
	$(GITLEAKS) git . --redact --no-banner

secrets-staged: ## Scan what is staged for the next commit. The pre-commit hook runs this.
	$(GITLEAKS) git . --pre-commit --staged --redact --no-banner

hooks: ## Install the git hooks, which stop a commit that would add a secret.
	git config core.hooksPath .githooks
	@echo "Hooks installed from .githooks."
