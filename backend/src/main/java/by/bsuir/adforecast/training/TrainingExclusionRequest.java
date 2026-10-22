package by.bsuir.adforecast.training;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TrainingExclusionRequest(

        @NotNull
        Boolean inTraining,

        @NotNull
        Boolean isTest,

        @NotBlank
        String reason
) {
}
