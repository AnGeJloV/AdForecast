package by.bsuir.adforecast.campaign;

import java.math.BigDecimal;

public interface CampaignSummaryProjection {

    Long getCampaignId();

    String getCampaignName();

    String getStatus();

    BigDecimal getBudget();

    String getCurrency();

    String getPlatform();

    Long getUploadCount();

    Long getDaysCount();

    BigDecimal getTotalCost();

    BigDecimal getTotalRevenue();

    BigDecimal getActualRoi();

    Long getTotalImpressions();

    Long getTotalClicks();

    BigDecimal getLatestForecastRoi();

    BigDecimal getLatestPaybackMonths();

    BigDecimal getLatestPaybackRatio();

    String getLatestVerdict();
}
