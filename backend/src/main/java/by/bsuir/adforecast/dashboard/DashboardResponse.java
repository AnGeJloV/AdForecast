package by.bsuir.adforecast.dashboard;

import java.math.BigDecimal;
import java.util.List;

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
        long forecastsCompleted,
        List<PlanFactRow> planVsFact,
        BigDecimal planVsFactAvgDeviation
) {

    public record PlanFactRow(
            long campaignId,
            String campaignName,
            String currency,
            String platform,
            BigDecimal actualRoi,
            BigDecimal forecastRoi,
            BigDecimal deviation,
            long days,
            long horizon,
            boolean comparable
    ) {
    }
}
