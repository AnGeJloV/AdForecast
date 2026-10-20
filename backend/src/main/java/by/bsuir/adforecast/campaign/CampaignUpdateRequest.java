package by.bsuir.adforecast.campaign;

import by.bsuir.adforecast.domain.CampaignStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CampaignUpdateRequest(

        @NotBlank
        @Size(max = 255)
        String campaignName,

        @NotNull
        @Positive
        BigDecimal budget,

        @NotBlank
        @Size(max = 10)
        String currency,

        @NotNull
        CampaignStatus status
) {
}
