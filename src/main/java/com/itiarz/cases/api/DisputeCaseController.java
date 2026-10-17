package com.itiarz.cases.api;

import com.itiarz.cases.api.CaseDtos.*;
import com.itiarz.cases.service.DisputeCaseService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped case API (spec §7). {@code @tenant.selected()} answers 403 TENANT_NOT_SELECTED for
 * tenant-less tokens; transition permissions depend on the target status and are checked in the service.
 */
@RestController
@RequestMapping("/api/cases")
public class DisputeCaseController {
    private final DisputeCaseService service;

    public DisputeCaseController(DisputeCaseService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@tenant.selected() and hasAuthority('case:read')")
    public List<CaseSummary> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("@tenant.selected() and hasAuthority('case:read')")
    public CaseDetail get(@PathVariable UUID id) {
        return service.findOne(id);
    }

    @PostMapping
    @PreAuthorize("@tenant.selected() and hasAuthority('case:write')")
    public ResponseEntity<CaseDetail> create(@Valid @RequestBody CreateCaseRequest request) {
        var result = service.create(request);
        return ResponseEntity.created(URI.create("/api/cases/" + result.id())).body(result);
    }

    @PostMapping("/{id}/evidence")
    @PreAuthorize("@tenant.selected() and hasAuthority('evidence:write')")
    public CaseDetail addEvidence(@PathVariable UUID id, @Valid @RequestBody AddEvidenceRequest request) {
        return service.addEvidence(id, request);
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("@tenant.selected() and hasAnyAuthority('case:write', 'case:approve', 'case:close')")
    public CaseDetail transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return service.transition(id, request);
    }

    @PatchMapping("/{id}/assignee")
    @PreAuthorize("@tenant.selected() and hasAuthority('case:assign')")
    public CaseDetail assign(@PathVariable UUID id, @RequestBody AssigneeRequest request) {
        return service.assign(id, request);
    }
}
