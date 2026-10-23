package by.bsuir.adforecast.mlclient;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MlPredictResponse(
        @JsonProperty("model_name") String modelName,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("payback_ratio") double paybackRatio,
        @JsonProperty("forecast_roi") double forecastRoi,
        @JsonProperty("interval_lower") double intervalLower,
        @JsonProperty("interval_upper") double intervalUpper,
        @JsonProperty("agreement") double agreement,
        @JsonProperty("member_spread") double memberSpread,
        @JsonProperty("payback_days") Double paybackDays,
        @JsonProperty("payback_months") Double paybackMonths,
        @JsonProperty("payback_within_duration") boolean paybackWithinDuration,
        @JsonProperty("profitable_budget_min") Double profitableBudgetMin,
        @JsonProperty("profitable_budget_max") Double profitableBudgetMax,
        @JsonProperty("profitable_at_any_budget") boolean profitableAtAnyBudget,
        @JsonProperty("budget_out_of_range") boolean budgetOutOfRange,
        @JsonProperty("day_budget_min") double dayBudgetMin,
        @JsonProperty("day_budget_max") double dayBudgetMax,
        @JsonProperty("platform_unknown") boolean platformUnknown
) {
}
