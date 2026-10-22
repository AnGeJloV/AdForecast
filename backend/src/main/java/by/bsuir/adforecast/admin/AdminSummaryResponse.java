package by.bsuir.adforecast.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AdminSummaryResponse(
        long usersTotal,
        long usersActive,
        long campaignsPlanned,
        long campaignsActive,
        long campaignsFinished,
        long forecastsTotal,
        long forecastsCompleted,
        long uploadsTotal,
        long exportsTotal,
        long modelVersions,
        boolean mlServiceAvailable,
        String activeModelVersion,
        LocalDateTime activeModelTrainedAt,
        BigDecimal activeModelMape
) {
}
