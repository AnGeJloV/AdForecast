package by.bsuir.adforecast.forecast;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ForecastListItem(
        Long id,
        Long campaignId,
        String campaignName,
        LocalDateTime startedAt,
        String status,
        String verdict,
        BigDecimal paybackRatio,
        BigDecimal forecastRoi,
        BigDecimal intervalLower,
        BigDecimal intervalUpper,
        Integer durationDays,
        BigDecimal paybackMonths,
        BigDecimal mape
) {
}
