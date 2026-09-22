-- Credentials: the rows the request path reads to learn which Tenant it is acting for (ADR-0006).
--
-- Neither table is under row-level security, and that is the point rather than an omission: a
-- credential is resolved before there is a Tenant, so a policy keyed on app.current_tenant would
-- hide every row from the one query that needs one. What limits exposure instead is the shape of
-- the lookup -- an API key is found by the hash of the key presented, a staff user by username --
-- and the fact that no secret is stored in the clear.
--
-- The request-path role may only read; issuing and revoking are the control plane's (ADR-0015).

------------------------------------------------------------------------------------------------
-- API keys: long-lived, opaque, for machine callers. Stored as the SHA-256 of the key, which is
-- enough because the key itself is 256 bits of randomness rather than something a person chose.
------------------------------------------------------------------------------------------------

CREATE TABLE api_key (
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    id          uuid        NOT NULL,
    key_hash    text        NOT NULL UNIQUE CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    label       text        NOT NULL CHECK (length(label) BETWEEN 1 AND 100),
    permissions text[]      NOT NULL CHECK (cardinality(permissions) > 0),
    created_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz,

    PRIMARY KEY (tenant_id, id)
);

------------------------------------------------------------------------------------------------
-- Staff users: people, who log in with a password and are issued a short-lived JWT. The username
-- is unique across Tenants because it is the credential's identity; the Tenant is the row's.
------------------------------------------------------------------------------------------------

CREATE TABLE staff_user (
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    id            uuid        NOT NULL,
    username      text        NOT NULL UNIQUE CHECK (length(username) BETWEEN 1 AND 100),
    password_hash text        NOT NULL,
    permissions   text[]      NOT NULL CHECK (cardinality(permissions) > 0),
    created_at    timestamptz NOT NULL DEFAULT now(),
    revoked_at    timestamptz,

    PRIMARY KEY (tenant_id, id)
);

GRANT SELECT                 ON api_key, staff_user TO nostro_app;
GRANT SELECT, INSERT, UPDATE ON api_key, staff_user TO nostro_control;
