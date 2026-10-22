package by.bsuir.adforecast.forecast;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ForecastListItem(
        Long id,
        Long campaignId,
        String campaignName,
        LocalDateTime startedAt,
        String status,
        String modelVersion,
        Integer horizonDays,
        BigDecimal forecastRoi,
        BigDecimal paybackMonths,
        BigDecimal paybackProbability,
        BigDecimal mape
) {
}
