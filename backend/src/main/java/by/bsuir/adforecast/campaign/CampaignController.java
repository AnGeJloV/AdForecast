package by.bsuir.adforecast.campaign;

import by.bsuir.adforecast.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private static final String ADMIN_AUTHORITY = "ROLE_администратор";

    private final CampaignService campaignService;

    public CampaignController(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @GetMapping
    public List<CampaignSummaryResponse> list(Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        if (isAdmin(authentication)) {
            return campaignService.listAll();
        }
        return campaignService.listForUser(principal.userId());
    }

    @GetMapping("/{id}")
    public CampaignDetailResponse get(@PathVariable("id") long id, Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return campaignService.get(id, isAdmin(authentication), principal.userId());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('campaign.manage')")
    public CampaignSummaryResponse create(@Valid @RequestBody CampaignCreateRequest request,
                                          Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return campaignService.create(principal.userId(), request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('campaign.manage')")
    public CampaignSummaryResponse update(@PathVariable("id") long id,
                                          @Valid @RequestBody CampaignUpdateRequest request,
                                          Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return campaignService.update(id, isAdmin(authentication), principal.userId(), request);
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
    }
}
