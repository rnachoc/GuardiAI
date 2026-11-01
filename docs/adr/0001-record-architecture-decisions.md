# 1. Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodríguez Caro

## Context

Guardia combines Java, TypeScript, several tool servers, a policy engine and an evaluation harness, under safety constraints that are easy to weaken by accident. The reason behind a choice is often worth more than the code that implements it, and it is lost when it only lives in a commit message or a chat.

## Decision

Every significant decision, including every significant dependency, is recorded as a short numbered file in `docs/adr`, with its context, the decision and its consequences. An accepted record is never rewritten. When a decision changes, a new record supersedes the old one, and the old one's status says which record replaced it. The [index](README.md) lists every record, and a test fails if one is missing from it or if the numbering has a gap.

## Consequences

- A reader can learn why the system looks the way it does without asking anyone.
- Reversing a decision leaves a trail, which makes it harder to reverse one silently.
- Each record costs a few minutes to write, and the habit has to be kept up in every phase.
