# Event contracts

JSON Schema (draft 2020-12) of events exchanged through Kafka. Every event is wrapped in an envelope
with `eventId`, `eventType`, `version`, `occurredAt` and `payload`.

| Topic | Producer | Consumer | Schema |
|---|---|---|---|
| `transfers.check` | finflow-core | finflow-ml | `transfer-check-requested.schema.json` |
| `transfers.check.result` | finflow-ml | finflow-core | `fraud-check-completed.schema.json` |
| `transfers.status-changed` | finflow-core | downstream services | `transfer-status-changed.schema.json` |

Rules:

- Amounts are strings with four decimals, timestamps are ISO-8601 in UTC, identifiers are UUIDs.
- Consumers ignore unknown fields, so adding an optional field does not need a new version.
- A breaking change increments `version` and is published together with the old one until consumers migrate.
- Every event has a unique `eventId`, consumers deduplicate on it.
- `examples/` holds one valid message per schema, both services validate them in their tests.
