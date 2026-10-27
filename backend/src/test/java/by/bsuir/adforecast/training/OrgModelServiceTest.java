package by.bsuir.adforecast.training;

import by.bsuir.adforecast.domain.MlModel;
import by.bsuir.adforecast.domain.Organization;
import by.bsuir.adforecast.mlclient.MlServiceClient;
import by.bsuir.adforecast.mlclient.TrainAcceptedResponse;
import by.bsuir.adforecast.mlclient.TrainStatusResponse;
import by.bsuir.adforecast.mlclient.TrainedModel;
import by.bsuir.adforecast.repo.MlModelRepository;
import by.bsuir.adforecast.repo.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgModelServiceTest {

    private static final long TIMEOUT_MS = 5000;

    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private MlModelRepository mlModelRepository;
    @Mock
    private TrainingService trainingService;
    @Mock
    private MlServiceClient mlServiceClient;

    @InjectMocks
    private OrgModelService service;

    private MlModel activeGeneral() {
        MlModel model = new MlModel();
        model.setId(1L);
        model.setModelName("adforecast_roi");
        model.setIsActive(true);
        return model;
    }

    private TrainingService.TrainingPayload payload(String modelName, String signature) {
        return new TrainingService.TrainingPayload(List.of(Map.of("campaign_id", 1L)), modelName,
                Map.of("campaign_ids", List.of(1L), "rule", "правило", "hash", "abc"), 1, signature);
    }

    private TrainedModel trainedModel(String modelName, String version) {
        return new TrainedModel(modelName, version, OffsetDateTime.now().toString(), 8.06, 0.94, 0.05,
                200, 10, null, null, null, null,
                Map.of("campaign_ids", List.of(1L), "rule", "правило"));
    }

    @Test
    void emptySystemTriggersGeneralModelBootstrap() {
        when(mlModelRepository.findByOrganizationIdIsNull()).thenReturn(List.of());
        when(trainingService.buildPayload(null)).thenReturn(payload("adforecast_roi", "signature-1"));
        when(trainingService.submit(any(), any(), any()))
                .thenReturn(new TrainAcceptedResponse("task-1", "ACCEPTED"));
        when(mlServiceClient.trainStatus("task-1"))
                .thenReturn(new TrainStatusResponse("task-1", "COMPLETED", trainedModel("adforecast_roi", "v1"), null));

        service.triggerIfStale(1L);

        verify(trainingService, timeout(TIMEOUT_MS)).registerModel(isNull(), any(TrainedModel.class),
                eq("signature-1"));
    }

    @Test
    void bootstrapIsSkippedWhenModelsExist() {
        when(mlModelRepository.findByOrganizationIdIsNull()).thenReturn(List.of(activeGeneral()));

        service.triggerIfStale(1L);

        verify(mlModelRepository, timeout(TIMEOUT_MS)).findByOrganizationIdIsNull();
        verify(trainingService, never()).buildPayload(any());
    }

    @Test
    void organizationModelIsNotRetrainedWhenSignatureUnchanged() {
        Organization organization = new Organization();
        organization.setId(1L);
        organization.setName("Организация «Демо»");
        MlModel active = new MlModel();
        active.setTrainingSignature("campaigns=5;days=20");
        when(mlModelRepository.findByOrganizationIdIsNull()).thenReturn(List.of(activeGeneral()));
        when(organizationRepository.findById(1L)).thenReturn(Optional.of(organization));
        when(mlModelRepository.findByOrganizationIdAndIsActiveTrue(1L)).thenReturn(Optional.of(active));
        when(trainingService.datasetSignature(organization)).thenReturn("campaigns=5;days=20");

        service.triggerIfStale(1L);

        verify(trainingService, timeout(TIMEOUT_MS)).datasetSignature(organization);
        verify(trainingService, never()).buildPayload(any());
    }

    @Test
    void organizationModelIsNotRetrainedRightAfterPreviousTraining() {
        Organization organization = new Organization();
        organization.setId(1L);
        MlModel active = new MlModel();
        active.setTrainingSignature("campaigns=5;days=20");
        active.setTrainedAt(LocalDateTime.now());
        when(mlModelRepository.findByOrganizationIdIsNull()).thenReturn(List.of(activeGeneral()));
        when(organizationRepository.findById(1L)).thenReturn(Optional.of(organization));
        when(mlModelRepository.findByOrganizationIdAndIsActiveTrue(1L)).thenReturn(Optional.of(active));
        when(trainingService.datasetSignature(organization)).thenReturn("campaigns=6;days=25");

        service.triggerIfStale(1L);

        verify(trainingService, timeout(TIMEOUT_MS)).datasetSignature(organization);
        verify(trainingService, never()).buildPayload(any());
    }

    @Test
    void organizationModelIsRetrainedWhenDataChanged() {
        Organization organization = new Organization();
        organization.setId(1L);
        organization.setName("Организация «Демо»");
        MlModel active = new MlModel();
        active.setTrainingSignature("campaigns=5;days=20");
        active.setTrainedAt(LocalDateTime.now().minusHours(2));
        when(mlModelRepository.findByOrganizationIdIsNull()).thenReturn(List.of(activeGeneral()));
        when(organizationRepository.findById(1L)).thenReturn(Optional.of(organization));
        when(mlModelRepository.findByOrganizationIdAndIsActiveTrue(1L)).thenReturn(Optional.of(active));
        when(trainingService.datasetSignature(organization)).thenReturn("campaigns=6;days=25");
        when(trainingService.buildPayload(organization)).thenReturn(payload("adforecast_org_1", "signature-2"));
        when(trainingService.submit(any(), any(), any()))
                .thenReturn(new TrainAcceptedResponse("task-2", "ACCEPTED"));
        when(mlServiceClient.trainStatus("task-2"))
                .thenReturn(new TrainStatusResponse("task-2", "COMPLETED", trainedModel("adforecast_org_1", "v3"),
                        null));

        service.triggerIfStale(1L);

        verify(trainingService, timeout(TIMEOUT_MS)).registerModel(isNull(), any(TrainedModel.class),
                eq("signature-2"));
    }

    @Test
    void missingOrganizationIsIgnoredQuietly() {
        when(mlModelRepository.findByOrganizationIdIsNull()).thenReturn(List.of(activeGeneral()));
        when(organizationRepository.findById(99L)).thenReturn(Optional.empty());

        service.triggerIfStale(99L);

        verify(organizationRepository, timeout(TIMEOUT_MS)).findById(99L);
        verify(trainingService, never()).buildPayload(any());
    }
}
