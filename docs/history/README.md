# Project history — archived planning documents

These five documents scaffolded scalacv's migration from **Scala 2.11 / sbt 0.13 / vendored OpenCV
3.0.0-rc1** to **Scala 3 / Mill / OpenCV 4.13**. That migration is complete. They are kept for the
record — how decisions were reached, what was tried, where research was wrong and corrected — but they
are **no longer live**. The live record is [`../../ROADMAP.md`](../../ROADMAP.md).

| Document | What it is |
|---|---|
| [`PLAN.md`](PLAN.md) | The original modernization plan. **Superseded** by `ROADMAP.md`, which corrects four of its "ground truth" claims (ROADMAP §3). |
| [`REVIEW.md`](REVIEW.md) | The adversarial review of the plan — 2 blockers, 20 should-fix, 19 nits — all since resolved. |
| [`NOTES-audit.md`](NOTES-audit.md) | Archaeology of the legacy 2.11 codebase. |
| [`NOTES-upstream.md`](NOTES-upstream.md) | Upstream OpenCV / bytedeco / ecosystem findings. |
| [`NOTES-experiments.md`](NOTES-experiments.md) | Orchestrator experiments E-3…E-11 — the executed verifications behind the ROADMAP's version pins. |

Moved here from the repository root on 2026-07-24 so a newcomer sees `README → ROADMAP → IDEAS`
rather than five overlapping planning files.
