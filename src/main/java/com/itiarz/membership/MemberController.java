package com.itiarz.membership;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itiarz.membership.MemberDtos.InviteRequest;
import com.itiarz.membership.MemberDtos.MemberView;
import com.itiarz.membership.MemberDtos.RoleChangeRequest;
import com.itiarz.security.RequestContext;
import com.itiarz.security.RequestContextHolder;

/** Team management inside the caller's tenant (spec §6.4). */
@RestController
@RequestMapping("/api/members")
public class MemberController {
    private final MembershipService service;

    public MemberController(MembershipService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@tenant.selected() and hasAuthority('member:read')")
    public List<MemberView> list() {
        return service.list(RequestContextHolder.requireTenantId());
    }

    @PostMapping("/invitations")
    @PreAuthorize("@tenant.selected() and hasAuthority('member:manage')")
    public ResponseEntity<MemberView> invite(@Valid @RequestBody InviteRequest request) {
        RequestContext context = RequestContextHolder.get();
        Membership membership = service.invite(context.tenantId(), request.email(), request.role(), context.userId());
        return ResponseEntity.created(URI.create("/api/members/" + membership.getId()))
                .body(service.view(membership));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@tenant.selected() and hasAuthority('member:manage')")
    public MemberView changeRole(@PathVariable UUID id, @Valid @RequestBody RoleChangeRequest request) {
        return service.changeRole(id, request.role());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@tenant.selected() and hasAuthority('member:manage')")
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        service.remove(id);
        return ResponseEntity.noContent().build();
    }
}
