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
        List<ChannelRow> channels,
        List<PlanFactRow> planVsFact,
        /** Средняя абсолютная ошибка по сопоставимым кампаниям, процентные пункты ROI. */
        BigDecimal planVsFactAvgError,
        /** Среднее знаковое отклонение: положительное — прогноз занижает. */
        BigDecimal planVsFactBias,
        /** Средняя относительная ошибка к прогнозу, проценты. */
        BigDecimal planVsFactAvgErrorPercent
) {

    public record ChannelRow(
            String platform,
            long finishedCampaigns,
            BigDecimal totalCost,
            BigDecimal totalRevenue,
            BigDecimal avgRoi,
            long profitable,
            long unprofitable
    ) {
    }

    public record PlanFactRow(
            long campaignId,
            String campaignName,
            String currency,
            String platform,
            BigDecimal actualRoi,
            BigDecimal forecastRoi,
            BigDecimal deviation,
            long days,
            long durationDays,
            boolean comparable
    ) {
    }
}
