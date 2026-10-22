package by.bsuir.adforecast.campaign;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyMetricPoint(
        LocalDate date,
        BigDecimal cost,
        BigDecimal revenue,
        Integer impressions,
        Integer clicks
) {
}
