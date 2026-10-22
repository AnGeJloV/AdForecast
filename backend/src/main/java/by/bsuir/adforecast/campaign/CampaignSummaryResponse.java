package by.bsuir.adforecast.campaign;

import java.math.BigDecimal;

public record CampaignSummaryResponse(
        Long id,
        String campaignName,
        String status,
        BigDecimal budget,
        String currency,
        String platform,
        Long uploadCount,
        Long daysCount,
        BigDecimal totalCost,
        BigDecimal totalRevenue,
        BigDecimal actualRoi,
        Long totalImpressions,
        Long totalClicks,
        BigDecimal latestForecastRoi,
        BigDecimal latestPaybackMonths,
        BigDecimal latestPaybackProbability
) {

    public static CampaignSummaryResponse from(CampaignSummaryProjection projection) {
        return new CampaignSummaryResponse(
                projection.getCampaignId(),
                projection.getCampaignName(),
                projection.getStatus(),
                projection.getBudget(),
                projection.getCurrency(),
                projection.getPlatform(),
                projection.getUploadCount(),
                projection.getDaysCount(),
                projection.getTotalCost(),
                projection.getTotalRevenue(),
                projection.getActualRoi(),
                projection.getTotalImpressions(),
                projection.getTotalClicks(),
                projection.getLatestForecastRoi(),
                projection.getLatestPaybackMonths(),
                projection.getLatestPaybackProbability()
        );
    }
}
