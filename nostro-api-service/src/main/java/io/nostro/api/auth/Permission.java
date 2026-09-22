package io.nostro.api.auth;

/**
 * What a credential is allowed to do. An endpoint names the Permissions it requires with
 * {@link Requires}; a credential carries the ones it was issued with. The two surfaces of the
 * system are kept apart here: a ledger credential holds ledger Permissions, the control plane's
 * bootstrap credential holds {@link #CONTROL} and nothing else (ADR-0015).
 */
public enum Permission {
    LEDGER_READ,
    LEDGER_WRITE,
    CONTROL
}
