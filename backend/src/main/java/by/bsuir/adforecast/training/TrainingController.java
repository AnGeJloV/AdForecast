package by.bsuir.adforecast.training;

import by.bsuir.adforecast.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/training")
public class TrainingController {

    private final TrainingService trainingService;

    public TrainingController(TrainingService trainingService) {
        this.trainingService = trainingService;
    }

    @GetMapping("/preview")
    @PreAuthorize("hasAuthority('models.manage')")
    public TrainingPreviewResponse preview(@RequestParam(name = "scope", defaultValue = "GENERAL") String scope,
                                           @RequestParam(name = "organizationId", required = false) Long organizationId) {
        return trainingService.preview(scope, organizationId);
    }

    @PutMapping("/campaigns/{id}/flags")
    @PreAuthorize("hasAuthority('models.manage')")
    public void updateFlags(@PathVariable("id") long id,
                            @Valid @RequestBody TrainingExclusionRequest request,
                            Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        trainingService.updateFlags(id, principal.userId(), request);
    }

    @PostMapping("/start")
    @PreAuthorize("hasAuthority('models.manage')")
    public TrainingStartResponse start(@Valid @RequestBody(required = false) TrainingStartRequest request,
                                       Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return trainingService.start(principal.userId(), request);
    }

    @GetMapping("/tasks/{taskId}")
    @PreAuthorize("hasAuthority('models.manage')")
    public TrainingTaskResponse status(@PathVariable("taskId") String taskId, Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return trainingService.status(principal.userId(), taskId);
    }
}
