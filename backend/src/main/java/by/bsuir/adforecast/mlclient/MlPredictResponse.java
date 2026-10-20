package by.bsuir.adforecast.mlclient;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MlPredictResponse(
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("payback_ratio") double paybackRatio,
        @JsonProperty("forecast_roi") double forecastRoi,
        @JsonProperty("payback_months") Double paybackMonths,
        @JsonProperty("payback_days") Double paybackDays,
        @JsonProperty("payback_within_horizon") boolean paybackWithinHorizon,
        @JsonProperty("payback_probability") double paybackProbability
) {
}
