package by.bsuir.adforecast.upload;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MetricRow(LocalDate date, int impressions, int clicks, BigDecimal cost, BigDecimal revenue) {
}
