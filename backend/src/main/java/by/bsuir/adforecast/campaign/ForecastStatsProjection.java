package by.bsuir.adforecast.campaign;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface ForecastStatsProjection {

    Long getForecastCount();

    LocalDateTime getLastForecastAt();

    BigDecimal getLatestForecastRoi();

    BigDecimal getLatestPaybackMonths();

    BigDecimal getLatestPaybackRatio();

    String getLatestVerdict();
}
