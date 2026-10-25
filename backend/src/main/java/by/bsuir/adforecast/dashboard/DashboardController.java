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

    /**
     * @param granularity шаг корзины: DAY, WEEK, MONTH, QUARTER
     * @param limit       сколько последних корзин вернуть; без него — вся история
     */
    @GetMapping("/cashflow")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public List<CashflowPoint> cashflow(@RequestParam(defaultValue = "WEEK") String granularity,
                                        @RequestParam(required = false) Integer limit,
                                        Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return dashboardService.cashflow(principal.userId(), granularity, limit);
    }
}
