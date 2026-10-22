package by.bsuir.adforecast.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CashflowPoint(
        LocalDate weekStart,
        BigDecimal cost,
        BigDecimal revenue
) {
}
