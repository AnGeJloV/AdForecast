package by.bsuir.adforecast.forecast;

import by.bsuir.adforecast.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ForecastController {


    private final ForecastService forecastService;

    public ForecastController(ForecastService forecastService) {
        this.forecastService = forecastService;
    }

    @PostMapping("/api/campaigns/{campaignId}/forecasts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('forecast.run')")
    public ForecastResponse run(@PathVariable("campaignId") long campaignId,
                                @Valid @RequestBody ForecastRequest request,
                                Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return forecastService.run(campaignId, principal.userId(), request);
    }

    @GetMapping("/api/campaigns/{campaignId}/forecasts")
    public List<ForecastResponse> history(@PathVariable("campaignId") long campaignId,
                                          Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return forecastService.history(campaignId, principal.userId());
    }

    @GetMapping("/api/forecasts")
    public List<ForecastListItem> listAll(Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return forecastService.listAll(principal.userId());
    }

    @PostMapping("/api/forecasts/compare")
    @PreAuthorize("hasAuthority('forecast.run')")
    public List<CompareResponse> compare(@Valid @RequestBody CompareRequest request,
                                        Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return forecastService.compare(principal.userId(), request);
    }

}
