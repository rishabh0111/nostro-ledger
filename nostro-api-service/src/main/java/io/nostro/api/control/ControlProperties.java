package io.nostro.api.control;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the control plane reaches the database: as {@code nostro_control}, a role distinct from the
 * request path's (ADR-0015), over a pool of its own that nothing tenant-scoped ever borrows from.
 *
 * @param datasource       the control-plane connection: the request path's URL, a different role
 * @param seedDemoTenants  whether to seed the two demo Tenants at startup and print their keys;
 *                         on for {@code docker compose up}, off everywhere else
 */
@ConfigurationProperties("nostro.control")
public record ControlProperties(Datasource datasource, @DefaultValue("false") boolean seedDemoTenants) {

    public record Datasource(String url, String username, String password) {

        public Datasource {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("nostro.control.datasource.url is required");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("nostro.control.datasource.username is required");
            }
            if (password == null || password.isBlank()) {
                throw new IllegalArgumentException("nostro.control.datasource.password is required");
            }
        }
    }
}
