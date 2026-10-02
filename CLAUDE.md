# CLAUDE.md

Guidance for AI assistants (and humans) working in this repository.

## What this is
Inventory reservation service, plain Java 21 library (no framework). Business rules live in `README.md`.
Entry point: `Inventory.create(Clock, StockAlertListener)`.

## Contract rules (non-negotiable)
- Never modify anything under `src/main/java/com/store/inventory/api/`. A hook in `.claude/settings.json` enforces this.
- Never change the signature of `Inventory.create(Clock, StockAlertListener)`.
- The original tests in `InventoryServiceTest` must keep passing.

## Conventions
- Package by feature: `catalog`, `stock`, `reservation`, `alert`. `Inventory` only wires them.
- Time comes only from the injected `Clock`. Never call `Instant.now()` directly.
- Category rules live in one table (`CategoryPolicies`). New category = one new entry + its test.
- Must be thread-safe: concurrent orders on the same SKU must never oversell.
- Prefer the JDK over new dependencies.

## Workflow
- Run tests: `mvn test` (must be green before any commit).
- Gitflow: `feature/<kebab-name>` from `develop`, PR back into `develop`; `release/<version>` into `main`.
- Commits follow Conventional Commits: `<type>(<scope>): <description>`.
- Assumptions and trade-offs go in `DECISIONS.md`.
