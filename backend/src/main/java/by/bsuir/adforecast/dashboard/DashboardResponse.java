package by.bsuir.adforecast.dashboard;

import java.math.BigDecimal;

public record DashboardResponse(
        long campaignsTotal,
        long campaignsPlanned,
        long campaignsActive,
        long campaignsFinished,
        BigDecimal totalBudget,
        BigDecimal totalCost,
        BigDecimal totalRevenue,
        BigDecimal actualRoi,
        long profitableCampaigns,
        long unprofitableCampaigns,
        long forecastsTotal,
        long forecastsCompleted
) {
}
