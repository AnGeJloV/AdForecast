package by.bsuir.adforecast.forecast;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ForecastResponse(
        Long id,
        String status,
        LocalDateTime startedAt,
        String verdict,
        BigDecimal paybackRatio,
        BigDecimal forecastRoi,
        BigDecimal intervalLower,
        BigDecimal intervalUpper,
        BigDecimal agreement,
        BigDecimal paybackDays,
        BigDecimal paybackMonths,
        Boolean paybackWithinDuration,
        BigDecimal expectedProfit,
        BigDecimal budgetMin,
        BigDecimal budgetMax,
        boolean budgetAny,
        String modelScope,
        BigDecimal dayBudgetMin,
        BigDecimal dayBudgetMax,
        String dataNote,
        Integer durationDays,
        Integer startMonth,
        Integer historyDays,
        BigDecimal historyCost,
        BigDecimal historyRevenue,
        String ownModelVersion,
        String generalModelVersion,
        BigDecimal ownWeight,
        Integer ownCampaigns,
        String basis,
        BigDecimal mape
) {
}
