-- The ledger core: tables, the invariants they hold, and the roles that may touch them.
--
-- Runs as the owner role over the direct (unpooled) connection. The owner bypasses row-level
-- security by ownership and never serves a request. Everything a request does runs as nostro_app,
-- which has no BYPASSRLS, is not the owner, and holds exactly the grants below
-- (docs/research/rls-pooling.md section 3; ADR-0006).
--
-- Placeholders: ${app_password}, ${control_password}.

------------------------------------------------------------------------------------------------
-- Installation identity: the prefix of every Position token (ADR-0007).
-- Read once here, as the owner, because pg_control_system() may not be callable by the runtime
-- role and because a token must be namespaced by the cluster that assigned its xid8.
------------------------------------------------------------------------------------------------

CREATE TABLE installation (
    singleton         boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    system_identifier bigint  NOT NULL
);

INSERT INTO installation (system_identifier)
SELECT system_identifier FROM pg_control_system();

------------------------------------------------------------------------------------------------
-- Tenant. Not tenant-scoped: the one table that legitimately spans Tenants (ADR-0015).
------------------------------------------------------------------------------------------------

CREATE TABLE tenant (
    id         uuid        PRIMARY KEY,
    name       text        NOT NULL UNIQUE CHECK (length(name) BETWEEN 1 AND 100),
    created_at timestamptz NOT NULL DEFAULT now()
);

------------------------------------------------------------------------------------------------
-- Account. Not insert-only: a Constrained Account carries a running balance that exists only to
-- enforce the floor, never to serve a read (ADR-0004).
------------------------------------------------------------------------------------------------

CREATE TABLE account (
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    id            uuid        NOT NULL,
    code          text        NOT NULL CHECK (length(code) BETWEEN 1 AND 100),
    currency      text        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    constrained   boolean     NOT NULL,
    balance_minor bigint      NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, id),
    CONSTRAINT account_code_unique_per_tenant UNIQUE (tenant_id, code),
    -- A redundant key so that a Posting can reference (tenant_id, account_id, currency) and the
    -- schema, not the application, holds "a Posting is in its Account's Currency".
    CONSTRAINT account_currency_reference UNIQUE (tenant_id, id, currency),
    -- The floor. The guarded UPDATE refuses with zero rows; this refuses with SQLSTATE 23514, and
    -- it is what still holds when someone writes a new query without the guard.
    CONSTRAINT account_constrained_non_negative CHECK (NOT constrained OR balance_minor >= 0)
);

-- currency and constrained never change after creation. The runtime role also only holds
-- UPDATE on balance_minor (see grants), so this trigger is the barrier for every other role.
CREATE FUNCTION account_declarations_are_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_id   IS DISTINCT FROM OLD.tenant_id
    OR NEW.id          IS DISTINCT FROM OLD.id
    OR NEW.code        IS DISTINCT FROM OLD.code
    OR NEW.currency    IS DISTINCT FROM OLD.currency
    OR NEW.constrained IS DISTINCT FROM OLD.constrained
    OR NEW.created_at  IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'account % may change only its balance_minor', OLD.id
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'account_declarations_are_immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER account_declarations_are_immutable
    BEFORE UPDATE ON account
    FOR EACH ROW EXECUTE FUNCTION account_declarations_are_immutable();

------------------------------------------------------------------------------------------------
-- Entry and Posting. Insert-only: the runtime role holds no UPDATE or DELETE on either.
------------------------------------------------------------------------------------------------

CREATE TABLE entry (
    tenant_id         uuid        NOT NULL REFERENCES tenant (id),
    id                uuid        NOT NULL,
    reverses_entry_id uuid,
    description       text        CHECK (length(description) <= 500),
    -- The Position this Entry created: the xid8 of the transaction that recorded it. Assigned by
    -- Postgres inside that transaction, never by the application (ADR-0007).
    position          xid8        NOT NULL DEFAULT pg_current_xact_id(),
    recorded_at       timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, id),
    -- Composite: RLS does not cover referential integrity, so the Tenant travels with the key.
    CONSTRAINT entry_reverses_same_tenant
        FOREIGN KEY (tenant_id, reverses_entry_id) REFERENCES entry (tenant_id, id),
    -- An Entry can be reversed at most once.
    CONSTRAINT entry_reversed_at_most_once UNIQUE (tenant_id, reverses_entry_id),
    CONSTRAINT entry_does_not_reverse_itself CHECK (reverses_entry_id IS DISTINCT FROM id)
);

CREATE INDEX entry_position_idx ON entry (tenant_id, position);

CREATE TABLE posting (
    tenant_id    uuid    NOT NULL,
    id           uuid    NOT NULL,
    entry_id     uuid    NOT NULL,
    account_id   uuid    NOT NULL,
    currency     text    NOT NULL,
    amount_minor bigint  NOT NULL CHECK (amount_minor <> 0),
    -- Denormalised from entry so an Account's history pages by keyset without a join.
    position     xid8    NOT NULL DEFAULT pg_current_xact_id(),

    PRIMARY KEY (tenant_id, id),
    CONSTRAINT posting_belongs_to_entry
        FOREIGN KEY (tenant_id, entry_id) REFERENCES entry (tenant_id, id),
    -- A Posting can only reference an Account of its own Tenant, and only in that Account's
    -- Currency. A row in another Tenant cannot satisfy this constraint at all
    -- (docs/research/rls-pooling.md section 4).
    CONSTRAINT posting_applies_to_account_in_its_currency
        FOREIGN KEY (tenant_id, account_id, currency) REFERENCES account (tenant_id, id, currency)
);

CREATE INDEX posting_entry_idx ON posting (tenant_id, entry_id);
CREATE INDEX posting_account_history_idx ON posting (tenant_id, account_id, position DESC, id DESC);

-- An Entry balances to zero within each Currency it touches, checked at COMMIT over every Posting
-- of the Entry. The domain refuses an unbalanced Entry before it reaches here; this is the barrier
-- that makes the claim true for every write path, including ones nobody has written yet.
CREATE FUNCTION entry_balances_per_currency() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    unbalanced record;
BEGIN
    SELECT currency, sum(amount_minor) AS net
      INTO unbalanced
      FROM posting
     WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id
     GROUP BY currency
    HAVING sum(amount_minor) <> 0
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'entry % does not balance in %: net %',
              NEW.entry_id, unbalanced.currency, unbalanced.net
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'entry_balances_per_currency';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER entry_balances_per_currency
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION entry_balances_per_currency();

------------------------------------------------------------------------------------------------
-- Idempotency: a unique index, not a state machine (ADR-0005). Written in the Entry's own
-- transaction; a duplicate blocks on the index until the first commits. Retention is 30 days;
-- the sweep is not built yet.
------------------------------------------------------------------------------------------------

CREATE TABLE idempotency_record (
    tenant_id           uuid        NOT NULL REFERENCES tenant (id),
    idempotency_key     text        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    request_fingerprint text        NOT NULL CHECK (length(request_fingerprint) = 64),
    entry_id            uuid        NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, idempotency_key),
    CONSTRAINT idempotency_record_names_entry
        FOREIGN KEY (tenant_id, entry_id) REFERENCES entry (tenant_id, id)
);

------------------------------------------------------------------------------------------------
-- Outbox: written in the Entry's transaction from the very first Entry; drained by the relay with
-- WHERE published_at IS NULL ORDER BY position, never by cursor and never with SKIP LOCKED
-- (docs/research/ordering-and-watermarks.md section 3).
------------------------------------------------------------------------------------------------

CREATE TABLE outbox (
    tenant_id    uuid        NOT NULL REFERENCES tenant (id),
    id           uuid        NOT NULL,
    entry_id     uuid        NOT NULL,
    position     xid8        NOT NULL DEFAULT pg_current_xact_id(),
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    publish_seq  bigint,

    PRIMARY KEY (tenant_id, id),
    CONSTRAINT outbox_one_row_per_entry UNIQUE (tenant_id, entry_id),
    CONSTRAINT outbox_names_entry
        FOREIGN KEY (tenant_id, entry_id) REFERENCES entry (tenant_id, id),
    CONSTRAINT outbox_published_with_seq CHECK ((published_at IS NULL) = (publish_seq IS NULL))
);

CREATE INDEX outbox_unpublished_idx ON outbox (position) WHERE published_at IS NULL;

------------------------------------------------------------------------------------------------
-- Row-level security on every tenant-scoped table. ENABLE + FORCE so even the owner is subject.
--
-- nullif(..., '') matters: after a transaction-local set_config the setting reverts to the empty
-- string, not NULL, and ''::uuid raises. With nullif, no context matches no rows in both the
-- never-set and the reverted case (docs/research/rls-pooling.md section 3).
--
-- account gets FOR UPDATE; entry, posting, idempotency_record and outbox get FOR SELECT and
-- FOR INSERT only. There is no DELETE policy anywhere: default deny.
------------------------------------------------------------------------------------------------

ALTER TABLE account            ENABLE ROW LEVEL SECURITY;
ALTER TABLE account            FORCE  ROW LEVEL SECURITY;
ALTER TABLE entry              ENABLE ROW LEVEL SECURITY;
ALTER TABLE entry              FORCE  ROW LEVEL SECURITY;
ALTER TABLE posting            ENABLE ROW LEVEL SECURITY;
ALTER TABLE posting            FORCE  ROW LEVEL SECURITY;
ALTER TABLE idempotency_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_record FORCE  ROW LEVEL SECURITY;
ALTER TABLE outbox             ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox             FORCE  ROW LEVEL SECURITY;

CREATE POLICY account_tenant_select ON account FOR SELECT
    USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
CREATE POLICY account_tenant_insert ON account FOR INSERT
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
CREATE POLICY account_tenant_update ON account FOR UPDATE
    USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY entry_tenant_select ON entry FOR SELECT
    USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
CREATE POLICY entry_tenant_insert ON entry FOR INSERT
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY posting_tenant_select ON posting FOR SELECT
    USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
CREATE POLICY posting_tenant_insert ON posting FOR INSERT
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY idempotency_record_tenant_select ON idempotency_record FOR SELECT
    USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
CREATE POLICY idempotency_record_tenant_insert ON idempotency_record FOR INSERT
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY outbox_tenant_select ON outbox FOR SELECT
    USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
CREATE POLICY outbox_tenant_insert ON outbox FOR INSERT
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

------------------------------------------------------------------------------------------------
-- Roles. nostro_app is the request path; nostro_control is the control plane (ADR-0015). Neither
-- owns anything, neither bypasses RLS. Roles are cluster-wide, so create them only if absent.
------------------------------------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nostro_app') THEN
        CREATE ROLE nostro_app LOGIN PASSWORD '${app_password}'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nostro_control') THEN
        CREATE ROLE nostro_control LOGIN PASSWORD '${control_password}'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO nostro_app, nostro_control;
GRANT SELECT ON installation TO nostro_app, nostro_control;

-- The request path. SELECT and INSERT on the ledger tables; on account, UPDATE of the running
-- balance and nothing else. The REVOKEs are already true by omission and are written out so the
-- insert-only barrier reads as a decision rather than an accident.
GRANT SELECT, INSERT ON account, entry, posting, idempotency_record, outbox TO nostro_app;
GRANT UPDATE (balance_minor) ON account TO nostro_app;
REVOKE UPDATE, DELETE, TRUNCATE ON entry, posting, idempotency_record, outbox FROM nostro_app;
REVOKE DELETE, TRUNCATE ON account FROM nostro_app;

-- The control plane: Tenants. Credentials arrive with their own migration.
GRANT SELECT, INSERT, UPDATE ON tenant TO nostro_control;

-- Request-path timeouts, so a hot Account queues briefly and fails retryably instead of holding
-- the connection pool (docs/research/hot-account-contention.md section 5). Estimates.
ALTER ROLE nostro_app SET lock_timeout = '1s';
ALTER ROLE nostro_app SET statement_timeout = '3s';
ALTER ROLE nostro_app SET idle_in_transaction_session_timeout = '10s';
