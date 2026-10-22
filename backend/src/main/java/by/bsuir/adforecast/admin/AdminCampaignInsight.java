package by.bsuir.adforecast.admin;

import by.bsuir.adforecast.campaign.CampaignDetailResponse;
import by.bsuir.adforecast.campaign.DailyMetricPoint;
import by.bsuir.adforecast.forecast.ForecastResponse;

import java.util.List;

public record AdminCampaignInsight(
        CampaignDetailResponse campaign,
        List<DailyMetricPoint> metrics,
        List<ForecastResponse> forecasts
) {
}
