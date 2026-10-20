package by.bsuir.adforecast.forecast;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ForecastResponse(
        Long id,
        String status,
        LocalDateTime startedAt,
        String modelVersion,
        Integer horizonDays,
        BigDecimal confidenceLevel,
        Boolean useSeasonality,
        BigDecimal forecastRoi,
        BigDecimal paybackMonths,
        Double paybackDays,
        Boolean paybackWithinHorizon,
        BigDecimal paybackProbability,
        BigDecimal mape
) {
}
