package by.bsuir.adforecast.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/admin/audit")
public class AuditController {

    private static final Set<String> CATEGORIES = Set.of("all", "users", "data", "models", "backups");

    private final AuditLogRepository auditLogRepository;

    public AuditController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit.view')")
    public PageResponse<AuditEntryResponse> entries(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    @RequestParam(defaultValue = "all") String category,
                                                    @RequestParam(defaultValue = "") String search,
                                                    @RequestParam(defaultValue = "") String sort,
                                                    @RequestParam(defaultValue = "") String direction) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);
        String safeCategory = CATEGORIES.contains(category) ? category : "all";
        return PageResponse.of(auditLogRepository.search(safeCategory, likePattern(search), sort, direction, safePage, safeSize));
    }

    private static String likePattern(String search) {
        String escaped = search.trim().toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
