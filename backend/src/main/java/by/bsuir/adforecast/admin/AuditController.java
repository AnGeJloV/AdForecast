package by.bsuir.adforecast.admin;

import by.bsuir.adforecast.repo.AuditActionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit")
public class AuditController {

    private final AuditActionRepository auditActionRepository;

    public AuditController(AuditActionRepository auditActionRepository) {
        this.auditActionRepository = auditActionRepository;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit.view')")
    public PageResponse<AuditEntryResponse> entries(@RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return PageResponse.of(auditActionRepository.findEntries(pageable));
    }
}
