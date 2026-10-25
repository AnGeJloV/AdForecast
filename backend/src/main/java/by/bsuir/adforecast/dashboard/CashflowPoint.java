package by.bsuir.adforecast.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CashflowPoint(
        LocalDate periodStart,
        BigDecimal cost,
        BigDecimal revenue
) {
}
