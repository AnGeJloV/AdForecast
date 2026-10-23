package by.bsuir.adforecast.forecast;

import java.math.BigDecimal;

public record CompareResponse(
        String platform,
        BigDecimal budget,
        Integer durationDays,
        Integer startMonth,
        String verdict,
        BigDecimal paybackRatio,
        BigDecimal forecastRoi,
        BigDecimal intervalLower,
        BigDecimal intervalUpper,
        BigDecimal paybackMonths,
        Boolean paybackWithinDuration,
        BigDecimal profitableBudgetMin,
        BigDecimal profitableBudgetMax,
        boolean profitableAtAnyBudget,
        String basis
) {
}
