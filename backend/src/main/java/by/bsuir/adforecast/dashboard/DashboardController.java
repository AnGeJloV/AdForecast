package by.bsuir.adforecast.dashboard;

import by.bsuir.adforecast.security.AuthPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('dashboard.view')")
    public DashboardResponse dashboard(Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return dashboardService.build(principal.userId());
    }

    @GetMapping("/cashflow")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public List<CashflowPoint> cashflow(@RequestParam(defaultValue = "12") int weeks,
                                        Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return dashboardService.cashflow(principal.userId(), weeks);
    }
}
