package by.bsuir.adforecast.training;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.domain.Campaign;
import by.bsuir.adforecast.domain.MlModel;
import by.bsuir.adforecast.domain.Organization;
import by.bsuir.adforecast.mlclient.MlServiceClient;
import by.bsuir.adforecast.mlclient.TrainAcceptedResponse;
import by.bsuir.adforecast.mlclient.TrainStatusResponse;
import by.bsuir.adforecast.mlclient.TrainedModel;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.MlModelRepository;
import by.bsuir.adforecast.repo.OrganizationRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainingServiceTest {

    private static final String METRICS_FRAGMENT = "metric_date AS metric_date";
    private static final String INCLUDED_FRAGMENT = "ROUND(SUM(m.revenue)";
    private static final String EXCLUDED_FRAGMENT = "NOT c.in_training OR c.is_test";
    private static final String SIGNATURE_FRAGMENT = "AS campaigns";

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private MlModelRepository mlModelRepository;
    @Mock
    private MlServiceClient mlServiceClient;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private TrainingService service;

    private List<Map<String, Object>> metricsRows(int campaigns, int daysPerCampaign, double cost) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String[] platforms = {"VK Реклама", "VK Реклама", "Email-платформа", "Google Ads", "Telegram Ads"};
        for (int campaign = 1; campaign <= campaigns; campaign++) {
            for (int day = 1; day <= daysPerCampaign; day++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("campaign_id", (long) campaign);
                row.put("platform", platforms[(campaign - 1) % platforms.length]);
                row.put("budget", new BigDecimal("10000"));
                row.put("metric_date", Date.valueOf("2026-08-%02d".formatted(day)));
                row.put("cost", cost);
                row.put("revenue", 150.0);
                rows.add(row);
            }
        }
        return rows;
    }

    private void stubSignature() {
        when(jdbcTemplate.queryForMap(contains(SIGNATURE_FRAGMENT), any(Object[].class))).thenReturn(Map.of(
                "campaigns", 5L, "days", 20L, "last_day", Date.valueOf("2026-08-04")));
    }

    @Test
    void buildPayloadCreatesCutoffRecordsWithSnapshot() {
        when(jdbcTemplate.queryForList(contains(METRICS_FRAGMENT)))
                .thenReturn(metricsRows(5, 4, 100.0));
        stubSignature();

        TrainingService.TrainingPayload payload = service.buildPayload(null);

        assertThat(payload.records()).hasSize(20);
        assertThat(payload.campaignCount()).isEqualTo(5);
        assertThat(payload.modelName()).isEqualTo("adforecast_roi");
        assertThat(payload.snapshot()).containsKeys("campaign_ids", "rule", "hash");
        assertThat(String.valueOf(payload.snapshot().get("hash"))).hasSize(64);
        assertThat(payload.signature()).isEqualTo("campaigns=5;days=20;last=2026-08-04");

        Map<String, Object> cutoff = payload.records().get(3);
        assertThat(cutoff.get("cutoff_days")).isEqualTo(3);
        assertThat(cutoff.get("cost_to_date")).isEqualTo(300.0);
        assertThat(cutoff.get("revenue_to_date")).isEqualTo(450.0);
        assertThat(cutoff.get("target_ratio")).isEqualTo(1.5);
        assertThat(cutoff.get("duration_days")).isEqualTo(4);
        assertThat(cutoff.get("start_month")).isEqualTo(8);
    }

    @Test
    void buildPayloadNamesOrganizationModel() {
        Organization organization = new Organization();
        organization.setId(5L);
        organization.setName("Организация «Демо»");
        when(jdbcTemplate.queryForList(contains(METRICS_FRAGMENT)))
                .thenReturn(metricsRows(5, 4, 100.0));
        stubSignature();

        TrainingService.TrainingPayload payload = service.buildPayload(organization);

        assertThat(payload.modelName()).isEqualTo("adforecast_org_5");
        assertThat(String.valueOf(payload.snapshot().get("rule"))).contains("«Демо»");
    }

    @Test
    void buildPayloadExcludesCampaignsWithoutCost() {
        List<Map<String, Object>> rows = metricsRows(5, 4, 100.0);
        rows.forEach(row -> {
            if (((Number) row.get("campaign_id")).longValue() == 1L) {
                row.put("cost", 0.0);
            }
        });
        when(jdbcTemplate.queryForList(contains(METRICS_FRAGMENT))).thenReturn(rows);

        assertThatThrownBy(() -> service.buildPayload(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("недостаточно данных для обучения: 16 записей (минимум 20)");
    }

    @Test
    void buildPayloadRejectsDatasetBelowRecordThreshold() {
        when(jdbcTemplate.queryForList(contains(METRICS_FRAGMENT)))
                .thenReturn(metricsRows(5, 3, 100.0));

        assertThatThrownBy(() -> service.buildPayload(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("недостаточно данных для обучения: 15 записей (минимум 20)");
    }

    @Test
    void startSubmitsPayloadAndRecordsAudit() {
        when(jdbcTemplate.queryForList(contains(METRICS_FRAGMENT)))
                .thenReturn(metricsRows(5, 4, 100.0));
        stubSignature();
        when(mlServiceClient.train(anyList(), anyInt(), anyDouble(), anyString(), anyMap()))
                .thenReturn(new TrainAcceptedResponse("task-1", "ACCEPTED"));

        TrainingStartResponse response = service.start(9L, new TrainingStartRequest("GENERAL", null, null, null));

        assertThat(response.taskId()).isEqualTo("task-1");
        verify(mlServiceClient).train(anyList(), eq(10), eq(0.2), eq("adforecast_roi"), anyMap());
        verify(auditService).record(eq(9L), eq("запуск обучения"), eq("модель"), any(),
                contains("записей 20"));
    }

    @Test
    void startRegistersSignatureForLaterCompletion() {
        when(jdbcTemplate.queryForList(contains(METRICS_FRAGMENT)))
                .thenReturn(metricsRows(5, 4, 100.0));
        stubSignature();
        when(mlServiceClient.train(anyList(), anyInt(), anyDouble(), anyString(), anyMap()))
                .thenReturn(new TrainAcceptedResponse("task-2", "ACCEPTED"));
        when(mlModelRepository.save(any(MlModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.start(9L, new TrainingStartRequest("GENERAL", null, null, null));
        when(mlServiceClient.trainStatus("task-2")).thenReturn(completedStatus());

        service.status(9L, "task-2");

        ArgumentCaptor<MlModel> saved = ArgumentCaptor.forClass(MlModel.class);
        verify(mlModelRepository).save(saved.capture());
        assertThat(saved.getValue().getTrainingSignature()).isEqualTo("campaigns=5;days=20;last=2026-08-04");
    }

    @Test
    void statusWithoutCompletionDoesNotRegisterAnything() {
        when(mlServiceClient.trainStatus("task-3"))
                .thenReturn(new TrainStatusResponse("task-3", "RUNNING", null, null));

        TrainingTaskResponse response = service.status(9L, "task-3");

        assertThat(response.status()).isEqualTo("RUNNING");
        assertThat(response.model()).isNull();
        verify(mlModelRepository, never()).save(any(MlModel.class));
    }

    @Test
    void registerModelDeactivatesPreviousVersionAndAudits() {
        MlModel oldModel = new MlModel();
        oldModel.setId(1L);
        oldModel.setIsActive(true);
        when(mlModelRepository.findByModelNameAndModelVersion("adforecast_roi", "v2")).thenReturn(Optional.empty());
        when(mlModelRepository.findByOrganizationIdIsNull()).thenReturn(List.of(oldModel));
        when(mlModelRepository.save(any(MlModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MlModel registered = service.registerModel(9L, trainedModel("adforecast_roi", "v2"),
                "campaigns=5;days=20");

        assertThat(registered.getIsActive()).isTrue();
        assertThat(registered.getModelVersion()).isEqualTo("v2");
        assertThat(oldModel.getIsActive()).isFalse();
        verify(mlModelRepository).save(oldModel);
        verify(auditService).record(eq(9L), eq("обучение завершено"), eq("модель"), any(),
                contains("v2"));
    }

    @Test
    void registerModelKeepsAlreadyActiveVersion() {
        MlModel active = new MlModel();
        active.setId(2L);
        active.setIsActive(true);
        when(mlModelRepository.findByModelNameAndModelVersion("adforecast_roi", "v1")).thenReturn(Optional.of(active));

        MlModel registered = service.registerModel(9L, trainedModel("adforecast_roi", "v1"), "signature");

        assertThat(registered).isSameAs(active);
        verify(mlModelRepository, never()).save(any(MlModel.class));
        verify(auditService, never()).record(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void updateFlagsSavesCampaignAndAudits() {
        Campaign campaign = new Campaign();
        campaign.setId(1L);
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFlags(1L, 9L, new TrainingExclusionRequest(false, true, "тестовая кампания"));

        assertThat(campaign.getInTraining()).isFalse();
        assertThat(campaign.getIsTest()).isTrue();
        verify(auditService).record(eq(9L), eq("изменение флагов обучения"), eq("кампания"), eq(1L),
                contains("тестовая кампания"));
    }

    @Test
    void updateFlagsRequiresExistingCampaign() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateFlags(1L, 9L,
                new TrainingExclusionRequest(true, false, "причина")))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void previewAggregatesPlatformsAndPeriods() {
        when(jdbcTemplate.queryForList(contains(METRICS_FRAGMENT)))
                .thenReturn(metricsRows(3, 4, 100.0));
        List<Map<String, Object>> included = List.of(
                includedRow(1L, "VK — продвижение", "VK Реклама"),
                includedRow(2L, "VK — охват", "VK Реклама"),
                includedRow(3L, "Email — рассылка", "Email-платформа"));
        List<Map<String, Object>> excluded = List.of(Map.of(
                "campaign_id", 9L,
                "campaign_name", "Тест",
                "platform", "VK Реклама",
                "in_training", false,
                "is_test", true,
                "days", 10L,
                "period_start", Date.valueOf("2026-09-01"),
                "period_end", Date.valueOf("2026-09-10")));
        when(jdbcTemplate.queryForList(contains(INCLUDED_FRAGMENT), any(Object[].class))).thenReturn(included);
        when(jdbcTemplate.queryForList(contains(EXCLUDED_FRAGMENT), any(Object[].class))).thenReturn(excluded);

        TrainingPreviewResponse preview = service.preview("GENERAL", null);

        assertThat(preview.campaignCount()).isEqualTo(3);
        assertThat(preview.recordCount()).isEqualTo(12);
        assertThat(preview.byPlatform())
                .extracting(TrainingPreviewResponse.PlatformRow::platform,
                        TrainingPreviewResponse.PlatformRow::campaigns,
                        TrainingPreviewResponse.PlatformRow::records)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Email-платформа", 1L, 4L),
                        org.assertj.core.groups.Tuple.tuple("VK Реклама", 2L, 8L));
        assertThat(preview.periodStart()).isEqualTo("2026-08-01");
        assertThat(preview.periodEnd()).isEqualTo("2026-08-31");
        assertThat(preview.included()).hasSize(3);
        assertThat(preview.excluded()).hasSize(1);
        assertThat(preview.excluded().get(0).inTraining()).isFalse();
        assertThat(preview.excluded().get(0).isTest()).isTrue();
    }

    @Test
    void resolveScopeRequiresOrganizationForOrganizationScope() {
        assertThatThrownBy(() -> service.resolveScope("ORGANIZATION", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("укажите организацию");

        when(organizationRepository.findById(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveScope("ORGANIZATION", 5L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private TrainStatusResponse completedStatus() {
        return new TrainStatusResponse("task-2", "COMPLETED", trainedModel("adforecast_roi", "v2"), null);
    }

    private TrainedModel trainedModel(String modelName, String version) {
        return new TrainedModel(modelName, version, OffsetDateTime.now().toString(), 8.06, 0.94, 0.05,
                200, 10, 5.77, 0.97, 13.94, 0.87,
                Map.of("campaign_ids", List.of(1L, 2L, 3L, 4L, 5L), "rule", "все организации"));
    }

    private Map<String, Object> includedRow(long id, String name, String platform) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("campaign_id", id);
        row.put("campaign_name", name);
        row.put("platform", platform);
        row.put("budget", new BigDecimal("10000"));
        row.put("days", 4L);
        row.put("ratio", new BigDecimal("1.5000"));
        row.put("period_start", Date.valueOf("2026-08-01"));
        row.put("period_end", Date.valueOf("2026-08-31"));
        return row;
    }
}
