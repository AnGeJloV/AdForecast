package by.bsuir.adforecast.forecast;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ForecastRequest(

        @NotNull
        @Min(1)
        @Max(730)
        Integer horizonDays,

        @NotNull
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        BigDecimal confidenceLevel,

        @NotNull
        Boolean useSeasonality,

        @Min(1)
        @Max(12)
        Integer startMonth
) {
}
