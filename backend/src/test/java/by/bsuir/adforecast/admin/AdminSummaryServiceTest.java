package by.bsuir.adforecast.admin;

import by.bsuir.adforecast.domain.MlModel;
import by.bsuir.adforecast.mlclient.MlModelInfo;
import by.bsuir.adforecast.mlclient.MlServiceClient;
import by.bsuir.adforecast.repo.MlModelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSummaryServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private MlModelRepository mlModelRepository;
    @Mock
    private MlServiceClient mlServiceClient;

    @InjectMocks
    private AdminSummaryService service;

    private Map<String, Object> counts() {
        return Map.of(
                "users_total", 10L, "users_active", 9L,
                "campaigns_planned", 8L, "campaigns_active", 6L, "campaigns_finished", 816L,
                "forecasts_total", 112L, "forecasts_completed", 100L,
                "uploads_total", 500L, "exports_total", 14L);
    }

    @Test
    void summaryMapsCountsAndActiveGeneralModel() {
        MlModel active = new MlModel();
        active.setModelVersion("v1");
        active.setTrainedAt(LocalDateTime.of(2026, 10, 25, 12, 0));
        active.setQualityMetric(new BigDecimal("8.06"));
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(counts());
        when(mlModelRepository.findByOrganizationIdIsNullAndIsActiveTrue()).thenReturn(Optional.of(active));
        when(mlModelRepository.countByOrganizationIdIsNull()).thenReturn(1L);
        when(mlServiceClient.models()).thenReturn(List.of());

        AdminSummaryResponse response = service.summary();

        assertThat(response.usersTotal()).isEqualTo(10);
        assertThat(response.usersActive()).isEqualTo(9);
        assertThat(response.campaignsFinished()).isEqualTo(816);
        assertThat(response.forecastsCompleted()).isEqualTo(100);
        assertThat(response.exportsTotal()).isEqualTo(14);
        assertThat(response.modelVersions()).isEqualTo(1);
        assertThat(response.mlServiceAvailable()).isTrue();
        assertThat(response.activeModelVersion()).isEqualTo("v1");
        assertThat(response.activeModelMape()).isEqualByComparingTo("8.06");
    }

    @Test
    void summaryReportsUnavailableMlServiceAndNoModel() {
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(counts());
        when(mlModelRepository.findByOrganizationIdIsNullAndIsActiveTrue()).thenReturn(Optional.empty());
        when(mlModelRepository.countByOrganizationIdIsNull()).thenReturn(0L);
        when(mlServiceClient.models()).thenThrow(new RuntimeException("connection refused"));

        AdminSummaryResponse response = service.summary();

        assertThat(response.mlServiceAvailable()).isFalse();
        assertThat(response.activeModelVersion()).isNull();
        assertThat(response.activeModelMape()).isNull();
    }

    private MlModelInfo info(String version) {
        return new MlModelInfo("adforecast_roi", version, "2026-10-25T12:00:00Z", 8.06, 0.94, 0.05,
                200, 10, List.of(), null, null, null, null, null);
    }
}
