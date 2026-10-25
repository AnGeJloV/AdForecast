package by.bsuir.adforecast.forecast;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record CompareRequest(

        @NotNull
        @Size(min = 1, max = 10)
        List<CompareVariant> variants
) {

    public record CompareVariant(

            @NotBlank
            String platform,

            /** Если не указана, используется базовая валюта модели. */
            String currency,

            @NotNull
            BigDecimal budget,

            @NotNull
            Integer durationDays,

            @NotNull
            Integer startMonth
    ) {
    }
}
