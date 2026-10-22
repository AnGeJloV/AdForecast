package by.bsuir.adforecast.admin;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.domain.MlModel;
import by.bsuir.adforecast.mlclient.MlModelInfo;
import by.bsuir.adforecast.mlclient.MlServiceClient;
import by.bsuir.adforecast.repo.MlModelRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AdminModelService {

    private final MlModelRepository mlModelRepository;
    private final MlServiceClient mlServiceClient;
    private final AuditService auditService;

    public AdminModelService(MlModelRepository mlModelRepository,
                             MlServiceClient mlServiceClient,
                             AuditService auditService) {
        this.mlModelRepository = mlModelRepository;
        this.mlServiceClient = mlServiceClient;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<AdminModelResponse> models() {
        Map<String, MlModelInfo> byVersion = mlServiceClient.models().stream()
                .collect(Collectors.toMap(MlModelInfo::version, Function.identity()));
        return mlModelRepository.findAllByOrderByTrainedAtDesc().stream()
                .map(model -> {
                    MlModelInfo info = byVersion.get(model.getModelVersion());
                    return new AdminModelResponse(
                            model.getId(),
                            model.getModelVersion(),
                            model.getTrainedAt(),
                            info == null ? model.getQualityMetric().doubleValue() : info.mape(),
                            Boolean.TRUE.equals(model.getIsActive()),
                            info != null,
                            snapshot(info)
                    );
                })
                .toList();
    }

    @Transactional
    public void activate(long modelId, long adminId) {
        MlModel target = mlModelRepository.findById(modelId)
                .orElseThrow(() -> new EntityNotFoundException("версия модели не найдена"));
        boolean available = mlServiceClient.models().stream()
                .anyMatch(info -> info.version().equals(target.getModelVersion()));
        if (!available) {
            throw new IllegalStateException("версия модели недоступна в сервисе прогнозирования");
        }
        MlModel previous = mlModelRepository.findByIsActiveTrue().orElse(null);
        for (MlModel model : mlModelRepository.findAllByOrderByTrainedAtDesc()) {
            if (Boolean.TRUE.equals(model.getIsActive())) {
                model.setIsActive(false);
                mlModelRepository.save(model);
            }
        }
        target.setIsActive(true);
        mlModelRepository.save(target);
        auditService.record(adminId, "активация версии модели", "модель", modelId,
                "версия " + target.getModelVersion() + " вместо "
                        + (previous == null ? "—" : previous.getModelVersion()));
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
