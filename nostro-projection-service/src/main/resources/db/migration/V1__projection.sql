-- The projection's own database: Balances built from the Entries on Kafka, and nothing else
-- (ADR-0008). It holds no copy of the ledger's tables and has no way to reach them.
--
-- Runs as this database's owner. Everything the service does at runtime runs as
-- nostro_projection, which owns nothing and has no BYPASSRLS: the same technique as the ledger's
-- request path, applied again rather than reinvented (docs/research/rls-pooling.md).
--
-- Placeholder: ${projection_password}.

------------------------------------------------------------------------------------------------
-- The Balance of every Account that has a Posting, per Tenant. An Account with no row has no
-- Postings yet; whether it exists at all is the ledger's to say, not this database's.
------------------------------------------------------------------------------------------------

CREATE TABLE projected_balance (
    tenant_id    uuid   NOT NULL,
    account_id   uuid   NOT NULL,
    currency     text   NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    amount_minor bigint NOT NULL,

    PRIMARY KEY (tenant_id, account_id)
);

------------------------------------------------------------------------------------------------
-- One row per Entry ever applied, written in the same transaction as its Balance moves. The
-- primary key is what makes applying an Entry twice impossible, however it arrives twice: a
-- relay that republished after a crash, a replay from offset zero, a rebuilt topic (ADR-0010).
-- Kept for as long as the projection can be replayed, which is always: a projection that cannot
-- be rebuilt from zero is one whose correctness cannot be shown
-- (docs/research/ordering-and-watermarks.md section 6).
--
-- Not the Idempotency Key. That stops a caller recording an Entry twice; this stops the
-- transport applying one twice.
------------------------------------------------------------------------------------------------

CREATE TABLE applied_entry (
    tenant_id  uuid        NOT NULL,
    entry_id   uuid        NOT NULL,
    position   xid8        NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, entry_id)
);

------------------------------------------------------------------------------------------------
-- How much of each Tenant's history the projection reflects: the largest Position applied. A
-- Tenant's Entries arrive on one partition in Position order (ADR-0007), so the largest applied
-- is also everything up to it. The ledger installation travels with it, because a Position is
-- meaningless outside the installation that assigned it.
------------------------------------------------------------------------------------------------

CREATE TABLE tenant_watermark (
    tenant_id           uuid   PRIMARY KEY,
    ledger_installation bigint NOT NULL,
    position            xid8   NOT NULL
);

------------------------------------------------------------------------------------------------
-- Where the consumer is, stored with what it has applied rather than in Kafka: Kafka's
-- exactly-once does not reach a consumer writing to an external store, and the documented answer
-- is to keep the offset in the same place as the output (docs/research/ordering-and-watermarks.md
-- section 6). Not Tenant data; a partition carries many Tenants.
------------------------------------------------------------------------------------------------

CREATE TABLE consumer_position (
    topic       text   NOT NULL,
    partition   int    NOT NULL CHECK (partition >= 0),
    next_offset bigint NOT NULL CHECK (next_offset >= 0),

    PRIMARY KEY (topic, partition)
);

------------------------------------------------------------------------------------------------
-- Row-level security on every Tenant-scoped table, ENABLE + FORCE, fail-closed on no context
-- exactly as in the ledger: nullif(..., '') because a reverted transaction-local setting is the
-- empty string, not NULL. There is no DELETE policy anywhere.
------------------------------------------------------------------------------------------------

ALTER TABLE projected_balance ENABLE ROW LEVEL SECURITY;
ALTER TABLE projected_balance FORCE  ROW LEVEL SECURITY;
ALTER TABLE applied_entry     ENABLE ROW LEVEL SECURITY;
ALTER TABLE applied_entry     FORCE  ROW LEVEL SECURITY;
ALTER TABLE tenant_watermark  ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_watermark  FORCE  ROW LEVEL SECURITY;

CREATE POLICY projected_balance_tenant ON projected_balance FOR ALL
    USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
CREATE POLICY applied_entry_tenant ON applied_entry FOR ALL
    USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
CREATE POLICY tenant_watermark_tenant ON tenant_watermark FOR ALL
    USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

------------------------------------------------------------------------------------------------
-- The runtime role. Roles are cluster-wide, so create it only if absent.
------------------------------------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nostro_projection') THEN
        CREATE ROLE nostro_projection LOGIN PASSWORD '${projection_password}'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO nostro_projection;
GRANT SELECT, INSERT, UPDATE ON projected_balance, applied_entry, tenant_watermark, consumer_position TO nostro_projection;
-- applied_entry is insert-only, as the ledger's Entries are.
REVOKE UPDATE ON applied_entry FROM nostro_projection;
REVOKE DELETE, TRUNCATE ON projected_balance, applied_entry, tenant_watermark, consumer_position FROM nostro_projection;

ALTER ROLE nostro_projection SET statement_timeout = '5s';
ALTER ROLE nostro_projection SET idle_in_transaction_session_timeout = '10s';
