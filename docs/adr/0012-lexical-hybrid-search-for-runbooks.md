# 12. Lexical hybrid search for runbooks

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodriguez Caro

## Context

The brief asks `mcp-runbooks` for hybrid search, full-text plus embeddings, over runbooks and past incidents, always citing document IDs. Neural embeddings need either an API call, which breaks the rule that everything runs without a key, or a model download of hundreds of megabytes, which breaks the clean-machine start. The corpus is small: a handful of runbooks now, and postmortems later.

## Decision

- Ranking combines BM25 over words (weight 0.6) with the cosine similarity of character-trigram TF-IDF vectors (weight 0.4). The trigram vectors tolerate different word forms and typos, such as "notifcations lagg", which BM25 alone misses.
- These vectors are lexical, not neural embeddings, and the documentation says so wherever search is described.
- Every result carries its document ID, and the text it returns is wrapped as untrusted data, like everything else read from the system. The runbooks are written so that none of them trips the injection detector.
- Runbooks live in `docs/runbooks`, one Markdown file each, with `id`, `title` and `services` in their front matter. Past incidents will live in `docs/incidents` in the same format.

## Consequences

- Search runs offline, deterministically and instantly, and its tests check that the right runbook ranks first for each incident family, typos included.
- Matching depends on shared words or word fragments. A question phrased with entirely different vocabulary will rank worse than it would with neural embeddings.
- Adding real embeddings later, behind an environment variable and the spend cap, is a new decision record. The tool's interface would not change.
