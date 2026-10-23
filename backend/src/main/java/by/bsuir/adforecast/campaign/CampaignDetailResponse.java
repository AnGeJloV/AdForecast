package by.bsuir.adforecast.campaign;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CampaignDetailResponse(
        Long id,
        String campaignName,
        String status,
        BigDecimal budget,
        String currency,
        Long accountId,
        String accountName,
        String platform,
        Long uploadCount,
        Long daysCount,
        BigDecimal totalCost,
        BigDecimal totalRevenue,
        BigDecimal actualRoi,
        Long totalImpressions,
        Long totalClicks,
        Long forecastCount,
        LocalDateTime lastForecastAt,
        BigDecimal latestForecastRoi,
        BigDecimal latestPaybackRatio,
        String latestVerdict
) {
}
