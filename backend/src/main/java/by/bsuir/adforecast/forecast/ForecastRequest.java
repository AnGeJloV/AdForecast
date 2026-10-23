package by.bsuir.adforecast.forecast;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record ForecastRequest(

        @Min(1)
        @Max(365)
        Integer durationDays,

        @Min(1)
        @Max(12)
        Integer startMonth,

        /** BLEND — своя модель вместе с общей (по умолчанию), OWN — только свои данные,
         *  GENERAL — только общая модель системы. */
        @Pattern(regexp = "BLEND|OWN|GENERAL")
        String modelScope
) {

    public String scopeOrDefault() {
        return modelScope == null ? "BLEND" : modelScope;
    }
}
