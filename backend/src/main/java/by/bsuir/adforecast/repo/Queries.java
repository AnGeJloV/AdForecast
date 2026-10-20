package by.bsuir.adforecast.repo;

public final class Queries {

    public static final String CAMPAIGN_SUMMARY_SELECT = """
            select v.campaign_id as campaignId,
                   v.campaign_name as campaignName,
                   v.status as status,
                   v.budget as budget,
                   v.currency as currency,
                   v.platform as platform,
                   v.upload_count as uploadCount,
                   v.days_count as daysCount,
                   v.total_cost as totalCost,
                   v.total_revenue as totalRevenue,
                   v.actual_roi as actualRoi,
                   v.total_impressions as totalImpressions,
                   v.total_clicks as totalClicks
            from v_campaign_summary v
            """;

    public static final String FORECAST_STATS_SELECT = """
            select v.campaign_id as campaignId,
                   v.forecast_count as forecastCount,
                   v.last_forecast_at as lastForecastAt,
                   v.latest_forecast_roi as latestForecastRoi,
                   v.latest_payback_months as latestPaybackMonths,
                   v.latest_payback_probability as latestPaybackProbability
            from v_forecast_stats v
            """;

    private Queries() {
    }
}
