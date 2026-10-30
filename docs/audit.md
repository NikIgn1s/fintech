# Audit

Envers records committed changes to accounts, transfers, budgets and category_rules in their
corresponding *_aud tables. Each revision has a shared revinfo row with timestamp, authenticated
user ID and the HTTP peer's IP address. Anonymous and background work has no authenticated actor.
Forwarded headers are not used as an audit identity or IP source.

V15 creates these tables through Flyway and validates them with Hibernate. Revision 1 contains
baseline snapshots of rows already present when V15 runs; its timestamp is the migration time,
not the original creation time. Earlier changes cannot be reconstructed from these snapshots.
New revisions use revision_seq. Optimistic-lock versions are excluded from snapshots.

Budget and category-rule writes use JPA so that Envers observes them. Their existing read queries
and API contracts are preserved. Budget writes lock the owning user before finding or creating a
budget, retaining serialization for concurrent updates of the same user/category/month/currency.
Deletion revisions retain the deleted category rule's state.

Manual transfer approval and rejection also append an audit_log record containing the reviewer,
peer IP, entity, action, reason and timestamp. This record, the transfer state, review, ledger entries
and outbox event share one transaction. Failed or rolled-back decisions leave no audit record.
The log rejects UPDATE and DELETE through a database trigger. Existing transfer_reviews remain
the source of truth for review decisions made before this migration; they are not backdated into
the new journal.

GET /api/v1/admin/audit?entity=transfer&id=<UUID>&page=0&size=20 returns revision snapshots and
business actions. ANALYST and ADMIN can access it; USER and anonymous callers cannot.

Supported entity values:
- transfer: UUID
- account: positive integer
- budget: positive integer
- category-rule: positive integer

Each response includes revisions, actions, totalRevisions, totalActions, page and size.
The two lists are paginated independently, newest first. Page starts at zero and size is 1..100.
Unknown entities and malformed identifiers return a ProblemDetail error. A valid identifier with
no history returns empty lists, including for deleted entities after their history is retained.
Revision types are ADD, MOD and DEL. Snapshot fields use database column names.

The journal's APPROVE and REJECT actions retain the review reason separately from the transfer's
fraud hold reason. Background fraud decisions appear in Envers history but do not masquerade as
manual analyst decisions in the business journal.

Ledger tables retain their existing append-only audit trail and are not audited again by Envers.
Passwords, authentication tokens and imported raw statement files are outside these snapshots.

The PostgreSQL integration tests verify actor/IP capture, baseline-compatible schema validation,
rule deletion snapshots, budget updates, manual decisions, rollback, duplicate-decision behavior,
journal immutability and access control.
