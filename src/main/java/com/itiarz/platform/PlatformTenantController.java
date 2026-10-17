package com.itiarz.platform;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itiarz.membership.MemberDtos.MemberView;
import com.itiarz.platform.PlatformDtos.CreateTenantRequest;
import com.itiarz.platform.PlatformDtos.InviteAdminRequest;
import com.itiarz.platform.PlatformDtos.TenantCreatedView;
import com.itiarz.platform.PlatformDtos.TenantView;
import com.itiarz.tenant.TenantStatus;

/** Platform admin tenant management (spec §6.3); platform permissions only, never tenant data. */
@RestController
@RequestMapping("/api/platform/tenants")
public class PlatformTenantController {
    private final PlatformTenantService service;

    public PlatformTenantController(PlatformTenantService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('tenant:create')")
    public List<TenantView> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('tenant:create')")
    public ResponseEntity<TenantCreatedView> create(@Valid @RequestBody CreateTenantRequest request) {
        TenantCreatedView created = service.create(request);
        return ResponseEntity.created(URI.create("/api/platform/tenants/" + created.id())).body(created);
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAuthority('tenant:suspend')")
    public TenantView suspend(@PathVariable UUID id) {
        return service.changeStatus(id, TenantStatus.SUSPENDED);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('tenant:suspend')")
    public TenantView activate(@PathVariable UUID id) {
        return service.changeStatus(id, TenantStatus.ACTIVE);
    }

    /** Role is always TENANT_ADMIN; a pending invitation for the e-mail is re-sent. */
    @PostMapping("/{id}/invitations")
    @PreAuthorize("hasAuthority('tenant:invite-admin')")
    public ResponseEntity<MemberView> inviteAdmin(@PathVariable UUID id, @Valid @RequestBody InviteAdminRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.inviteAdmin(id, request.email()));
    }
}
