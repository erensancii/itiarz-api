package com.itiarz.api;

import com.itiarz.api.CaseDtos.*;
import com.itiarz.service.DisputeCaseService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class DisputeCaseController {
    private final DisputeCaseService service;

    public DisputeCaseController(DisputeCaseService service) {
        this.service = service;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "mode", "DEMO_NO_AUTH");
    }

    @GetMapping("/cases")
    public List<CaseResponse> list() {
        return service.findAll();
    }

    @GetMapping("/cases/{id}")
    public CaseResponse get(@PathVariable UUID id) {
        return service.findOne(id);
    }

    @PostMapping("/cases")
    public ResponseEntity<CaseResponse> create(@Valid @RequestBody CreateCaseRequest request) {
        var result = service.create(request);
        return ResponseEntity.created(URI.create("/api/cases/" + result.id())).body(result);
    }

    @PostMapping("/cases/{id}/evidence")
    public CaseResponse addEvidence(@PathVariable UUID id, @Valid @RequestBody AddEvidenceRequest request) {
        return service.addEvidence(id, request);
    }

    @PatchMapping("/cases/{id}/status")
    public CaseResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest request) {
        return service.changeStatus(id, request);
    }
}
