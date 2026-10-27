package by.bsuir.adforecast.admin;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.domain.MlModel;
import by.bsuir.adforecast.domain.Organization;
import by.bsuir.adforecast.mlclient.MlModelInfo;
import by.bsuir.adforecast.mlclient.MlServiceClient;
import by.bsuir.adforecast.repo.MlModelRepository;
import by.bsuir.adforecast.repo.OrganizationRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminModelServiceTest {

    @Mock
    private MlModelRepository mlModelRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private MlServiceClient mlServiceClient;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private AdminModelService service;

    private MlModel generalModel(long id, String version, boolean active) {
        MlModel model = new MlModel();
        model.setId(id);
        model.setModelName("adforecast_roi");
        model.setModelVersion(version);
        model.setTrainedAt(LocalDateTime.of(2026, 10, 25, 12, 0).minusDays(id));
        model.setQualityMetric(new BigDecimal("8.06"));
        model.setIsActive(active);
        return model;
    }

    private MlModelInfo info(String version, Double mapeWithHistory) {
        return new MlModelInfo("adforecast_roi", version, "2026-10-25T12:00:00Z", 8.06, 0.94, 0.05,
                200, 10, List.of(), mapeWithHistory, null, null, null,
                Map.of("campaign_ids", List.of(1L, 2L), "rule", "правило", "hash", "abc"));
    }

    @Test
    void modelsListsGeneralVersionsWithMetricsAndSnapshot() {
        when(mlServiceClient.models()).thenReturn(List.of(info("v2", 5.77)));
        when(mlModelRepository.findByOrganizationIdIsNull()).thenReturn(List.of(
                generalModel(1L, "v2", true),
                generalModel(2L, "v1", false)));
        when(organizationRepository.count()).thenReturn(3L);
        when(mlModelRepository.findByOrganizationIdIsNotNull()).thenReturn(List.of());

        AdminModelsResponse response = service.models();

        assertThat(response.organizationsTotal()).isEqualTo(3);
        assertThat(response.organizationsWithOwnModel()).isZero();
        assertThat(response.models()).hasSize(2);
        AdminModelResponse newest = response.models().get(0);
        assertThat(newest.version()).isEqualTo("v2");
        assertThat(newest.scope()).isEqualTo("Общая модель");
        assertThat(newest.isActive()).isTrue();
        assertThat(newest.mapeWithHistory()).isEqualTo(5.77);
        assertThat(newest.available()).isTrue();
        assertThat(newest.snapshot().campaignIds()).containsExactly(1L, 2L);
        assertThat(newest.snapshot().hash()).isEqualTo("abc");
        AdminModelResponse old = response.models().get(1);
        assertThat(old.mape()).isEqualTo(8.06);
        assertThat(old.available()).isFalse();
        assertThat(old.snapshot()).isNull();
    }

    @Test
    void activateRequiresExistingAndAvailableVersion() {
        when(mlModelRepository.findById(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activate(5L, 9L)).isInstanceOf(EntityNotFoundException.class);

        MlModel target = generalModel(5L, "v1", false);
        when(mlModelRepository.findById(5L)).thenReturn(Optional.of(target));
        when(mlServiceClient.models()).thenReturn(List.of(info("v2", null)));
        assertThatThrownBy(() -> service.activate(5L, 9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("недоступна");
    }

    @Test
    void activateSwitchesActiveVersionInsideScopeAndAudits() {
        MlModel target = generalModel(5L, "v1", false);
        MlModel previous = generalModel(6L, "v2", true);
        when(mlModelRepository.findById(5L)).thenReturn(Optional.of(target));
        when(mlServiceClient.models()).thenReturn(List.of(info("v1", null)));
        when(mlModelRepository.findByOrganizationIdIsNull()).thenReturn(List.of(previous, target));
        when(mlModelRepository.save(any(MlModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.activate(5L, 9L);

        assertThat(previous.getIsActive()).isFalse();
        assertThat(target.getIsActive()).isTrue();
        verify(auditService).record(eq(9L), eq("активация версии модели"), eq("модель"), eq(5L),
                contains("v1 (общая) вместо v2"));
    }

    @Test
    void deleteRejectsActiveVersion() {
        MlModel active = generalModel(1L, "v1", true);
        when(mlModelRepository.findById(1L)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.delete(1L, 9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("нельзя удалить активную версию");

        verify(mlServiceClient, never()).deleteModel(any(), any());
        verify(mlModelRepository, never()).delete(any(MlModel.class));
    }

    @Test
    void deleteRemovesVersionFromRegistryAndService() {
        MlModel stale = generalModel(2L, "v0", false);
        when(mlModelRepository.findById(2L)).thenReturn(Optional.of(stale));

        service.delete(2L, 9L);

        verify(mlServiceClient).deleteModel("adforecast_roi", "v0");
        verify(mlModelRepository).delete(stale);
        verify(auditService).record(eq(9L), eq("удаление версии модели"), eq("модель"), eq(2L),
                contains("v0 (общая)"));
    }
}
