package by.bsuir.adforecast.dashboard;

import by.bsuir.adforecast.campaign.CampaignSummaryProjection;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.ForecastRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private ForecastRepository forecastRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private DashboardService service;

    private CampaignSummaryProjection summary(String status, String platform, String currency,
                                              String budget, String cost, String revenue, String roi) {
        CampaignSummaryProjection summary = org.mockito.Mockito.mock(CampaignSummaryProjection.class);
        when(summary.getStatus()).thenReturn(status);
        when(summary.getCurrency()).thenReturn(currency);
        when(summary.getBudget()).thenReturn(new BigDecimal(budget));
        when(summary.getTotalCost()).thenReturn(new BigDecimal(cost));
        when(summary.getTotalRevenue()).thenReturn(new BigDecimal(revenue));
        org.mockito.Mockito.lenient().when(summary.getPlatform()).thenReturn(platform);
        org.mockito.Mockito.lenient().when(summary.getActualRoi()).thenReturn(new BigDecimal(roi));
        return summary;
    }

    @Test
    void buildAggregatesCampaignsAndChannels() {
        List<CampaignSummaryProjection> summaries = List.of(
                summary("PLANNED", "VK Реклама", "BYN", "1000", "0", "0", "0"),
                summary("ACTIVE", "VK Реклама", "BYN", "2000", "500", "400", "-20"),
                summary("FINISHED", "VK Реклама", "BYN", "3000", "1000", "1500", "50"),
                summary("FINISHED", "Email-платформа", "BYN", "800", "720", "720", "-10"));
        when(campaignRepository.findUserSummaries(5L)).thenReturn(summaries);
        when(forecastRepository.countByUserId(5L)).thenReturn(3L);
        when(forecastRepository.countCompletedByUserId(5L)).thenReturn(2L);

        DashboardResponse response = service.build(5L);

        assertThat(response.campaignsTotal()).isEqualTo(4);
        assertThat(response.campaignsPlanned()).isEqualTo(1);
        assertThat(response.campaignsActive()).isEqualTo(1);
        assertThat(response.campaignsFinished()).isEqualTo(2);
        assertThat(response.totalBudget()).isEqualByComparingTo("6800");
        assertThat(response.totalCost()).isEqualByComparingTo("2220");
        assertThat(response.totalRevenue()).isEqualByComparingTo("2620");
        assertThat(response.actualRoi()).isEqualByComparingTo("18.02");
        assertThat(response.profitableCampaigns()).isEqualTo(1);
        assertThat(response.unprofitableCampaigns()).isEqualTo(1);
        assertThat(response.forecastsTotal()).isEqualTo(3);
        assertThat(response.forecastsCompleted()).isEqualTo(2);
        assertThat(response.channels()).hasSize(2);
        DashboardResponse.ChannelRow vk = response.channels().get(1);
        assertThat(vk.platform()).isEqualTo("VK Реклама");
        assertThat(vk.finishedCampaigns()).isEqualTo(1);
        assertThat(vk.profitable()).isEqualTo(1);
    }

    @Test
    void buildHandlesEmptyScopeWithoutRoi() {
        when(campaignRepository.findUserSummaries(5L)).thenReturn(List.of());
        when(forecastRepository.countByUserId(5L)).thenReturn(0L);
        when(forecastRepository.countCompletedByUserId(5L)).thenReturn(0L);

        DashboardResponse response = service.build(5L);

        assertThat(response.campaignsTotal()).isZero();
        assertThat(response.actualRoi()).isNull();
        assertThat(response.planVsFactAvgError()).isNull();
        assertThat(response.channels()).isEmpty();
    }

    @Test
    void planVsFactMarksComparableRowsAndComputesError() {
        when(campaignRepository.findUserSummaries(5L)).thenReturn(List.of());
        when(forecastRepository.countByUserId(5L)).thenReturn(0L);
        when(forecastRepository.countCompletedByUserId(5L)).thenReturn(0L);
        when(jdbcTemplate.queryForList(anyString(), eq(5L))).thenReturn(List.of(
                Map.of("campaign_id", 1L, "campaign_name", "Сопоставимая", "currency", "BYN",
                        "platform", "VK Реклама", "days_count", 40L, "actual_roi", new BigDecimal("62.00"),
                        "forecast_roi", new BigDecimal("50.00"), "duration_days", 38L),
                Map.of("campaign_id", 2L, "campaign_name", "Длинная", "currency", "BYN",
                        "platform", "VK Реклама", "days_count", 100L, "actual_roi", new BigDecimal("10.00"),
                        "forecast_roi", new BigDecimal("50.00"), "duration_days", 38L)));

        DashboardResponse response = service.build(5L);

        assertThat(response.planVsFact()).hasSize(2);
        assertThat(response.planVsFact().get(0).comparable()).isTrue();
        assertThat(response.planVsFact().get(0).deviation()).isEqualByComparingTo("12.00");
        assertThat(response.planVsFact().get(1).comparable()).isFalse();
        assertThat(response.planVsFact().get(1).deviation()).isNull();
        assertThat(response.planVsFactAvgError()).isEqualByComparingTo("12.00");
        assertThat(response.planVsFactBias()).isEqualByComparingTo("12.00");
        assertThat(response.planVsFactAvgErrorPercent()).isEqualByComparingTo("8.0000");
    }

    @Test
    void cashflowMergesCurrenciesIntoBaseAndLimitsBuckets() {
        when(jdbcTemplate.queryForList(anyString(), anyString(), eq(5L))).thenReturn(List.of(
                Map.of("period_start", LocalDate.of(2026, 10, 20), "currency", "BYN",
                        "cost", new BigDecimal("100"), "revenue", new BigDecimal("120")),
                Map.of("period_start", LocalDate.of(2026, 10, 20), "currency", "USD",
                        "cost", new BigDecimal("50"), "revenue", new BigDecimal("60")),
                Map.of("period_start", LocalDate.of(2026, 10, 13), "currency", "BYN",
                        "cost", new BigDecimal("10"), "revenue", new BigDecimal("12")),
                Map.of("period_start", LocalDate.of(2026, 10, 6), "currency", "BYN",
                        "cost", new BigDecimal("20"), "revenue", new BigDecimal("24"))));

        List<CashflowPoint> points = service.cashflow(5L, "WEEK", 2);

        assertThat(points).hasSize(2);
        assertThat(points.get(1).periodStart()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(points.get(1).cost()).isEqualByComparingTo("248.5650");
        assertThat(points.get(1).revenue()).isEqualByComparingTo("298.2780");
        assertThat(points.get(0).periodStart()).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void cashflowDefaultsToWeeklyBucketsForUnknownGranularity() {
        when(jdbcTemplate.queryForList(anyString(), eq("week"), eq(5L))).thenReturn(List.of());

        assertThat(service.cashflow(5L, "неизвестно", null)).isEmpty();
        assertThat(service.cashflow(5L, null, null)).isEmpty();

        when(jdbcTemplate.queryForList(anyString(), eq("month"), eq(5L))).thenReturn(List.of());
        assertThat(service.cashflow(5L, "MONTH", 3)).isEmpty();
    }
}
