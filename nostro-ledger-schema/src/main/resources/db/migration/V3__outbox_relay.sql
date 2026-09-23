-- The outbox relay: the one reader that drains every Tenant's outbox, in order (ADR-0009).
--
-- nostro_relay is a third runtime role beside nostro_app and nostro_control. Like them it owns
-- nothing and has no BYPASSRLS; what lets it see every Tenant's rows is two policies scoped to it
-- by name, on this one table. It cannot read an Entry, a Posting or an Account, and on the outbox
-- it can change two columns and nothing else.
--
-- Placeholder: ${relay_password}.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nostro_relay') THEN
        CREATE ROLE nostro_relay LOGIN PASSWORD '${relay_password}'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO nostro_relay;

-- The relay spans Tenants by design: it is the ordering authority for all of them, and the
-- Tenant is the message key it publishes under, not a context it acts in.
CREATE POLICY outbox_relay_select ON outbox FOR SELECT TO nostro_relay
    USING (true);
CREATE POLICY outbox_relay_update ON outbox FOR UPDATE TO nostro_relay
    USING (true) WITH CHECK (true);

GRANT SELECT (tenant_id, id, entry_id, position, payload, published_at, publish_seq) ON outbox TO nostro_relay;
GRANT UPDATE (published_at, publish_seq) ON outbox TO nostro_relay;

-- The order the relay published in. The relay is the only writer, so this is its own counter;
-- a gap after a crash is a batch that was produced and never marked, and was published again.
CREATE SEQUENCE outbox_publish_seq AS bigint;
GRANT USAGE ON SEQUENCE outbox_publish_seq TO nostro_relay;

-- A relay that holds a transaction open holds back the xmin every drain is gated on, for every
-- Tenant. Its transactions are one SELECT and one batch of UPDATEs; anything longer is a bug.
ALTER ROLE nostro_relay SET statement_timeout = '10s';
ALTER ROLE nostro_relay SET idle_in_transaction_session_timeout = '10s';
