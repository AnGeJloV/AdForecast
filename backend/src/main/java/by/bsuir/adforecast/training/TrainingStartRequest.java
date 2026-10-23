package by.bsuir.adforecast.training;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record TrainingStartRequest(

        @Pattern(regexp = "GENERAL|ORGANIZATION")
        String scope,

        Long organizationId,

        @Min(2)
        @Max(50)
        Integer ensembleSize,

        @DecimalMin("0.05")
        @DecimalMax("0.49")
        BigDecimal holdoutFraction
) {
}
