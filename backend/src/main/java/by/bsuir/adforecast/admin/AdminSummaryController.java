package by.bsuir.adforecast.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/summary")
public class AdminSummaryController {

    private final AdminSummaryService adminSummaryService;

    public AdminSummaryController(AdminSummaryService adminSummaryService) {
        this.adminSummaryService = adminSummaryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('admin.view')")
    public AdminSummaryResponse summary() {
        return adminSummaryService.summary();
    }
}
