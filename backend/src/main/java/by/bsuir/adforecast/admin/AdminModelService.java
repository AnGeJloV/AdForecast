package by.bsuir.adforecast.admin;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.domain.MlModel;
import by.bsuir.adforecast.mlclient.MlModelInfo;
import by.bsuir.adforecast.mlclient.MlServiceClient;
import by.bsuir.adforecast.repo.MlModelRepository;
import by.bsuir.adforecast.repo.OrganizationRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AdminModelService {

    private final MlModelRepository mlModelRepository;
    private final OrganizationRepository organizationRepository;
    private final MlServiceClient mlServiceClient;
    private final AuditService auditService;

    public AdminModelService(MlModelRepository mlModelRepository,
                             OrganizationRepository organizationRepository,
                             MlServiceClient mlServiceClient,
                             AuditService auditService) {
        this.mlModelRepository = mlModelRepository;
        this.organizationRepository = organizationRepository;
        this.mlServiceClient = mlServiceClient;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public AdminModelsResponse models() {
        Map<String, MlModelInfo> byKey = mlServiceClient.models().stream()
                .collect(Collectors.toMap(info -> info.modelName() + "/" + info.version(), Function.identity()));
        List<AdminModelResponse> general = mlModelRepository.findByOrganizationIdIsNull().stream()
                .sorted(Comparator.comparing(MlModel::getTrainedAt).reversed())
                .map(model -> {
                    MlModelInfo info = byKey.get(model.getModelName() + "/" + model.getModelVersion());
                    return new AdminModelResponse(
                            model.getId(),
                            model.getModelName(),
                            model.getOrganization() == null ? null : model.getOrganization().getId(),
                            model.getOrganization() == null ? null : model.getOrganization().getName(),
                            model.getOrganization() == null ? "Общая модель" : "Модель организации",
                            model.getModelVersion(),
                            model.getTrainedAt(),
                            info == null
                                    ? model.getQualityMetric() == null ? 0 : model.getQualityMetric().doubleValue()
                                    : info.mape(),
                            info == null ? null : info.mapeWithHistory(),
                            info == null ? null : info.mapePlanned(),
                            Boolean.TRUE.equals(model.getIsActive()),
                            info != null,
                            snapshot(info)
                    );
                })
                .toList();
        long organizationsTotal = organizationRepository.count();
        long organizationsWithOwnModel = mlModelRepository.findByOrganizationIdIsNotNull().stream()
                .map(model -> model.getOrganization().getId())
                .distinct()
                .count();
        return new AdminModelsResponse(general, organizationsTotal, organizationsWithOwnModel);
    }

    @Transactional
    public void activate(long modelId, long adminId) {
        MlModel target = mlModelRepository.findById(modelId)
                .orElseThrow(() -> new EntityNotFoundException("версия модели не найдена"));
        boolean available = mlServiceClient.models().stream()
                .anyMatch(info -> info.modelName().equals(target.getModelName())
                        && info.version().equals(target.getModelVersion()));
        if (!available) {
            throw new IllegalStateException("версия модели недоступна в сервисе прогнозирования");
        }
        String scopeName = target.getOrganization() == null
                ? "общая" : "организации «" + target.getOrganization().getName() + "»";
        List<MlModel> scope = target.getOrganization() == null
                ? mlModelRepository.findByOrganizationIdIsNull()
                : mlModelRepository.findByOrganizationId(target.getOrganization().getId());
        String previous = null;
        for (MlModel model : scope) {
            if (Boolean.TRUE.equals(model.getIsActive())) {
                previous = model.getModelVersion();
                model.setIsActive(false);
                mlModelRepository.save(model);
            }
        }
        target.setIsActive(true);
        mlModelRepository.save(target);
        auditService.record(adminId, "активация версии модели", "модель", modelId,
                "версия " + target.getModelVersion() + " (" + scopeName + ") вместо "
                        + (previous == null ? "—" : previous));
    }

    private AdminModelResponse.DatasetSnapshotResponse snapshot(MlModelInfo info) {
        if (info == null || info.datasetSnapshot() == null) {
            return null;
        }
        Map<String, Object> raw = info.datasetSnapshot();
        List<Long> campaignIds = raw.get("campaign_ids") == null
                ? List.of()
                : ((List<?>) raw.get("campaign_ids")).stream()
                        .map(id -> ((Number) id).longValue())
                        .toList();
        return new AdminModelResponse.DatasetSnapshotResponse(
                campaignIds,
                String.valueOf(raw.getOrDefault("rule", "")),
                String.valueOf(raw.getOrDefault("hash", ""))
        );
    }
}
