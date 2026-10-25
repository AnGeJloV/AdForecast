package by.bsuir.adforecast.admin;

import by.bsuir.adforecast.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/models")
public class AdminModelController {

    private final AdminModelService adminModelService;

    public AdminModelController(AdminModelService adminModelService) {
        this.adminModelService = adminModelService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('models.manage')")
    public AdminModelsResponse models() {
        return adminModelService.models();
    }

    @PostMapping("/{modelId}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('models.manage')")
    public void activate(@PathVariable("modelId") long modelId, Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        adminModelService.activate(modelId, principal.userId());
    }

    @DeleteMapping("/{modelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('models.manage')")
    public void delete(@PathVariable("modelId") long modelId, Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        adminModelService.delete(modelId, principal.userId());
    }
}
