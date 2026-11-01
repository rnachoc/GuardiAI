# 9. The project is called GuardiAI

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodriguez Caro

## Context

The brief named the project Guardia. The owner published the repository as `rnachoc/GuardiAI` and chose that as the project's name.

## Decision

- The product is called **GuardiAI** in every README, document, comment and user-facing text.
- Technical identifiers keep the lowercase `guardia`. These include the Compose project and network, the image names, environment variables such as `GUARDIA_MAX_SPEND_USD` (which the brief names), and the `guardia-chaos` command. Renaming them would break existing `.env` files and running stacks for no gain to a reader.
- ADRs 1 to 8 keep the old name, because an accepted record is not rewritten ([ADR 1](0001-record-architecture-decisions.md)). Where they say Guardia, they mean GuardiAI.

## Consequences

- Readers see one name everywhere they read prose.
- Identifiers and the name differ in case and spelling. This record explains why.
