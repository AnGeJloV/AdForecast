package by.bsuir.adforecast.admin;

import by.bsuir.adforecast.campaign.CampaignDetailResponse;
import by.bsuir.adforecast.campaign.CampaignService;
import by.bsuir.adforecast.campaign.DailyMetricPoint;
import by.bsuir.adforecast.forecast.ForecastResponse;
import by.bsuir.adforecast.forecast.ForecastService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/campaigns")
public class AdminCampaignController {

    private final CampaignService campaignService;
    private final ForecastService forecastService;

    public AdminCampaignController(CampaignService campaignService, ForecastService forecastService) {
        this.campaignService = campaignService;
        this.forecastService = forecastService;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin.view')")
    public AdminCampaignInsight insight(@PathVariable("id") long id) {
        CampaignDetailResponse campaign = campaignService.inspect(id);
        List<DailyMetricPoint> metrics = campaignService.inspectMetrics(id);
        List<ForecastResponse> forecasts = forecastService.historyForInspection(id);
        return new AdminCampaignInsight(campaign, metrics, forecasts);
    }
}
