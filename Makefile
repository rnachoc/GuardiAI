# Guardia. Docker and make are the only tools a machine needs (ADR 3): every check below runs
# in a container whose image is pinned by digest. `make` alone lists the targets.
#
# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>

SHELL := /bin/sh
.DEFAULT_GOAL := help

COMPOSE := docker compose
# Run tools as the current user, so nothing they write ends up owned by root.
DOCKER_RUN := docker run --rm --user $$(id -u):$$(id -g) --volume "$(CURDIR)":/repo --workdir /repo

PYTHON_IMAGE := python:3.13.18-alpine3.23@sha256:e4aeaca9081549f19bd5bc8605ed5ab257ba34f97e0471f8e09161f45b4b08b8
ACTIONLINT_IMAGE := rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667
YAMLLINT_IMAGE := pipelinecomponents/yamllint:0.35.27@sha256:f7f7b4f8824839e33690a880dbe20944128b0dc9c55522a0b92a29b3a905cecf
EDITORCONFIG_IMAGE := mstruebing/editorconfig-checker:4.0.2@sha256:2ba6232bfa0058f72f5f7d7816711590aa764afab1542af30b3ecb4553587918
SHELLCHECK_IMAGE := koalaman/shellcheck:v0.11.0@sha256:61862eba1fcf09a484ebcc6feea46f1782532571a34ed51fedf90dd25f925a8d
GITLEAKS_IMAGE := zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f

# A local gitleaks binary can replace the container: make secrets GITLEAKS=gitleaks
GITLEAKS ?= $(DOCKER_RUN) $(GITLEAKS_IMAGE)

SHELL_SCRIPTS := scripts/smoke.sh .githooks/pre-commit

.PHONY: help up down clean ps logs smoke test lint secrets secrets-staged hooks

help: ## List the targets.
	@awk 'BEGIN {FS = ":.*## "} /^[a-z-]+:.*## / {printf "  %-15s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

.env:
	cp .env.example .env
	@echo "Created .env from .env.example. Its values are local-only placeholders."

up: .env ## Start the stack and wait until every service is healthy.
	$(COMPOSE) up --detach --wait --wait-timeout 300

down: ## Stop the stack and keep its data.
	$(COMPOSE) down

clean: ## Stop the stack and delete its data.
	$(COMPOSE) down --volumes --remove-orphans

ps: ## Show the state of each service.
	$(COMPOSE) ps

logs: ## Follow the logs of every service.
	$(COMPOSE) logs --follow --tail 100

smoke: ## Prove each running service does its job, not only that it reports healthy.
	./scripts/smoke.sh

test: ## Run every test suite. Needs no API key and no running stack.
	$(DOCKER_RUN) --env PYTHONDONTWRITEBYTECODE=1 $(PYTHON_IMAGE) python3 -m unittest discover --start-directory tests --verbose

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
