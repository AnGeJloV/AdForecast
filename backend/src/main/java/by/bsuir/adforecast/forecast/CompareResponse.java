package by.bsuir.adforecast.forecast;

import java.math.BigDecimal;

public record CompareResponse(
        String platform,
        BigDecimal budget,
        Integer durationDays,
        Integer startMonth,
        BigDecimal forecastRoi,
        BigDecimal paybackMonths,
        BigDecimal paybackProbability,
        Boolean paybackWithinHorizon
) {
}
