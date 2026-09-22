package io.nostro.api.control;

import io.nostro.api.auth.Permission;
import io.nostro.api.auth.Requires;
import io.nostro.domain.TenantId;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The control plane over HTTP (ADR-0015). Every handler requires {@link Permission#CONTROL}, which
 * only the bootstrap credential holds, so no ledger credential reaches this surface; and the
 * bootstrap credential holds nothing else, so it reaches no other. The Tenant is a path segment
 * here and nowhere else in the API: this is the one caller that acts across Tenants, and it names
 * the one it means. The refusals here are the plainest {@code
 * ResponseStatusException}s until the error model lands.
 */
@RestController
class ControlPlaneController {

    private final ControlPlane controlPlane;

    ControlPlaneController(ControlPlane controlPlane) {
        this.controlPlane = controlPlane;
    }

    @Requires(Permission.CONTROL)
    @PostMapping("/control/tenants")
    ResponseEntity<TenantResponse> createTenant(@RequestBody CreateTenant request) {
        return switch (controlPlane.createTenant(request.name())) {
            case ControlPlane.TenantOutcome.Created created -> ResponseEntity
                    .created(URI.create("/control/tenants/" + created.tenant().id().value()))
                    .body(TenantResponse.of(created.tenant()));
            case ControlPlane.TenantOutcome.NameTaken taken ->
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "a Tenant named " + taken.name() + " exists");
            case ControlPlane.TenantOutcome.Invalid invalid ->
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.reason());
        };
    }

    /** Issues an API key. The response carries the key; nothing else ever will. */
    @Requires(Permission.CONTROL)
    @PostMapping("/control/tenants/{tenant}/api-keys")
    ResponseEntity<IssuedApiKeyResponse> issueApiKey(@PathVariable UUID tenant, @RequestBody IssueApiKey request) {
        return switch (controlPlane.issueApiKey(new TenantId(tenant), request.label(), permissions(request.permissions()))) {
            case ControlPlane.IssueOutcome.Issued issued -> ResponseEntity
                    .created(URI.create("/control/tenants/" + tenant + "/api-keys/" + issued.apiKey().id()))
                    .body(IssuedApiKeyResponse.of(issued.apiKey()));
            case ControlPlane.IssueOutcome.UnknownTenant unknown ->
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such Tenant");
            case ControlPlane.IssueOutcome.Invalid invalid ->
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.reason());
        };
    }

    /** Revokes the key. The row stays, marked; from the caller's side the credential is gone. */
    @Requires(Permission.CONTROL)
    @DeleteMapping("/control/tenants/{tenant}/api-keys/{id}")
    ResponseEntity<Void> revokeApiKey(@PathVariable UUID tenant, @PathVariable UUID id) {
        return revoked(controlPlane.revokeApiKey(new TenantId(tenant), id));
    }

    @Requires(Permission.CONTROL)
    @PostMapping("/control/tenants/{tenant}/staff-users")
    ResponseEntity<StaffUserResponse> createStaffUser(@PathVariable UUID tenant, @RequestBody CreateStaffUser request) {
        var outcome = controlPlane.createStaffUser(
                new TenantId(tenant), request.username(), request.password(), permissions(request.permissions()));
        return switch (outcome) {
            case ControlPlane.StaffOutcome.Created created -> ResponseEntity
                    .created(URI.create("/control/tenants/" + tenant + "/staff-users/" + created.staffUser().id()))
                    .body(StaffUserResponse.of(created.staffUser()));
            case ControlPlane.StaffOutcome.UnknownTenant unknown ->
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such Tenant");
            case ControlPlane.StaffOutcome.UsernameTaken taken ->
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "a staff user named " + taken.username() + " exists");
            case ControlPlane.StaffOutcome.Invalid invalid ->
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.reason());
        };
    }

    @Requires(Permission.CONTROL)
    @DeleteMapping("/control/tenants/{tenant}/staff-users/{id}")
    ResponseEntity<Void> revokeStaffUser(@PathVariable UUID tenant, @PathVariable UUID id) {
        return revoked(controlPlane.revokeStaffUser(new TenantId(tenant), id));
    }

    private static ResponseEntity<Void> revoked(ControlPlane.RevokeOutcome outcome) {
        return switch (outcome) {
            case ControlPlane.RevokeOutcome.Revoked revoked -> ResponseEntity.noContent().build();
            case ControlPlane.RevokeOutcome.Unknown unknown -> ResponseEntity.notFound().build();
        };
    }

    /** Permission names as the request spelt them; a name that is not a Permission is 400. */
    private static List<Permission> permissions(List<String> names) {
        try {
            return names == null ? List.of() : names.stream().map(Permission::valueOf).toList();
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown permission");
        }
    }

    private static List<String> names(Set<Permission> permissions) {
        return permissions.stream().map(Enum::name).sorted().toList();
    }

    record CreateTenant(String name) {
    }

    record IssueApiKey(String label, List<String> permissions) {
    }

    record CreateStaffUser(String username, String password, List<String> permissions) {
    }

    record TenantResponse(UUID id, String name) {
        static TenantResponse of(ControlPlane.Tenant tenant) {
            return new TenantResponse(tenant.id().value(), tenant.name());
        }
    }

    record IssuedApiKeyResponse(UUID id, UUID tenantId, String label, List<String> permissions, String key) {
        static IssuedApiKeyResponse of(ControlPlane.IssuedApiKey issued) {
            return new IssuedApiKeyResponse(issued.id(), issued.tenant().value(), issued.label(),
                    names(issued.permissions()), issued.key().value());
        }
    }

    record StaffUserResponse(UUID id, UUID tenantId, String username, List<String> permissions) {
        static StaffUserResponse of(ControlPlane.StaffUser user) {
            return new StaffUserResponse(user.id(), user.tenant().value(), user.username(), names(user.permissions()));
        }
    }
}
