package by.bsuir.adforecast.training;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.domain.Campaign;
import by.bsuir.adforecast.mlclient.TrainAcceptedResponse;
import by.bsuir.adforecast.mlclient.TrainStatusResponse;
import by.bsuir.adforecast.mlclient.TrainedModel;
import by.bsuir.adforecast.mlclient.MlServiceClient;
import by.bsuir.adforecast.mlclient.MlServiceException;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.MlModelRepository;
import by.bsuir.adforecast.domain.MlModel;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
public class TrainingService {

    private static final String MODEL_NAME = "adforecast_roi";
    private static final int MIN_RECORDS = 50;
    private static final String SELECTION_RULE =
            "status = FINISHED AND in_training AND NOT is_test AND SUM(cost) > 0";

    private static final String DATASET_SQL = """
            SELECT c.campaign_id AS campaign_id,
                   a.platform AS platform,
                   c.budget AS budget,
                   (u.period_end - u.period_start + 1) AS duration_days,
                   EXTRACT(MONTH FROM u.period_start)::int AS start_month,
                   ROUND(SUM(m.revenue) / NULLIF(SUM(m.cost), 0), 4) AS payback_ratio
            FROM campaigns c
            JOIN ad_accounts a ON a.account_id = c.account_id
            JOIN uploads u ON u.campaign_id = c.campaign_id
            JOIN daily_metrics m ON m.upload_id = u.upload_id
            WHERE c.status = 'FINISHED' AND c.in_training AND NOT c.is_test
            GROUP BY c.campaign_id, a.platform, c.budget, u.period_start, u.period_end
            HAVING SUM(m.cost) > 0
            ORDER BY c.campaign_id
            """;

    private static final String INCLUDED_SQL = """
            SELECT c.campaign_id AS campaign_id,
                   c.campaign_name AS campaign_name,
                   a.platform AS platform,
                   c.budget AS budget,
                   COUNT(DISTINCT m.metric_date) AS days,
                   ROUND(SUM(m.revenue) / NULLIF(SUM(m.cost), 0), 4) AS ratio,
                   MIN(u.period_start) AS period_start,
                   MAX(u.period_end) AS period_end
            FROM campaigns c
            JOIN ad_accounts a ON a.account_id = c.account_id
            JOIN uploads u ON u.campaign_id = c.campaign_id
            JOIN daily_metrics m ON m.upload_id = u.upload_id
            WHERE c.status = 'FINISHED' AND c.in_training AND NOT c.is_test
            GROUP BY c.campaign_id, c.campaign_name, a.platform, c.budget
            HAVING SUM(m.cost) > 0
            ORDER BY c.campaign_id
            """;

    private static final String EXCLUDED_SQL = """
            SELECT c.campaign_id AS campaign_id,
                   c.campaign_name AS campaign_name,
                   a.platform AS platform,
                   c.in_training AS in_training,
                   c.is_test AS is_test
            FROM campaigns c
            JOIN ad_accounts a ON a.account_id = c.account_id
            WHERE c.status = 'FINISHED'
              AND (NOT c.in_training OR c.is_test)
              AND EXISTS (
                  SELECT 1 FROM uploads u
                  JOIN daily_metrics m ON m.upload_id = u.upload_id
                  WHERE u.campaign_id = c.campaign_id
              )
            ORDER BY c.campaign_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final CampaignRepository campaignRepository;
    private final MlModelRepository mlModelRepository;
    private final MlServiceClient mlServiceClient;
    private final AuditService auditService;

    public TrainingService(JdbcTemplate jdbcTemplate,
                           CampaignRepository campaignRepository,
                           MlModelRepository mlModelRepository,
                           MlServiceClient mlServiceClient,
                           AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.campaignRepository = campaignRepository;
        this.mlModelRepository = mlModelRepository;
        this.mlServiceClient = mlServiceClient;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public TrainingPreviewResponse preview() {
        List<Map<String, Object>> records = jdbcTemplate.queryForList(DATASET_SQL);
        List<Map<String, Object>> included = jdbcTemplate.queryForList(INCLUDED_SQL);
        List<Map<String, Object>> excluded = jdbcTemplate.queryForList(EXCLUDED_SQL);

        Map<String, Long> recordsByPlatform = records.stream()
                .collect(Collectors.groupingBy(row -> String.valueOf(row.get("platform")), Collectors.counting()));
        Map<String, List<Map<String, Object>>> campaignsByPlatform = included.stream()
                .collect(Collectors.groupingBy(row -> String.valueOf(row.get("platform"))));
        List<TrainingPreviewResponse.PlatformRow> platformRows = campaignsByPlatform.entrySet().stream()
                .map(entry -> new TrainingPreviewResponse.PlatformRow(
                        entry.getKey(), entry.getValue().size(),
                        recordsByPlatform.getOrDefault(entry.getKey(), 0L)))
                .sorted(java.util.Comparator.comparing(TrainingPreviewResponse.PlatformRow::platform))
                .toList();

        String periodStart = included.stream().map(row -> row.get("period_start"))
                .map(Object::toString).min(String::compareTo).orElse(null);
        String periodEnd = included.stream().map(row -> row.get("period_end"))
                .map(Object::toString).max(String::compareTo).orElse(null);

        return new TrainingPreviewResponse(
                included.size(),
                records.size(),
                platformRows,
                periodStart,
                periodEnd,
                included.stream().map(row -> new TrainingPreviewResponse.IncludedCampaign(
                        ((Number) row.get("campaign_id")).longValue(),
                        String.valueOf(row.get("campaign_name")),
                        String.valueOf(row.get("platform")),
                        ((BigDecimal) row.get("budget")).toPlainString(),
                        ((Number) row.get("days")).longValue(),
                        String.valueOf(row.get("ratio")))).toList(),
                excluded.stream().map(row -> new TrainingPreviewResponse.ExcludedCampaign(
                        ((Number) row.get("campaign_id")).longValue(),
                        String.valueOf(row.get("campaign_name")),
                        String.valueOf(row.get("platform")),
                        Boolean.TRUE.equals(row.get("in_training")),
                        Boolean.TRUE.equals(row.get("is_test")))).toList()
        );
    }

    @Transactional
    public void updateFlags(long campaignId, long adminId, TrainingExclusionRequest request) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        campaign.setInTraining(request.inTraining());
        campaign.setIsTest(request.isTest());
        campaignRepository.save(campaign);
        auditService.record(adminId, "изменение флагов обучения", "кампания", campaignId,
                "in_training=" + request.inTraining() + ", is_test=" + request.isTest()
                        + ", причина: " + request.reason());
    }

    @Transactional
    public TrainingStartResponse start(long adminId, TrainingStartRequest request) {
        List<Map<String, Object>> dataset = jdbcTemplate.queryForList(DATASET_SQL);
        if (dataset.size() < MIN_RECORDS) {
            throw new IllegalArgumentException(
                    "недостаточно данных для обучения: " + dataset.size() + " записей (минимум " + MIN_RECORDS + ")");
        }
        List<Map<String, Object>> records = new ArrayList<>(dataset.size());
        TreeSet<Long> campaignIds = new TreeSet<>();
        StringBuilder canonical = new StringBuilder();
        for (Map<String, Object> row : dataset) {
            String platform = String.valueOf(row.get("platform"));
            double budget = ((BigDecimal) row.get("budget")).doubleValue();
            int duration = ((Number) row.get("duration_days")).intValue();
            int month = ((Number) row.get("start_month")).intValue();
            double ratio = ((BigDecimal) row.get("payback_ratio")).doubleValue();
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("platform", platform);
            record.put("budget", budget);
            record.put("duration_days", duration);
            record.put("start_month", month);
            record.put("payback_ratio", ratio);
            records.add(record);
            campaignIds.add(((Number) row.get("campaign_id")).longValue());
            canonical.append(platform).append('|')
                    .append(budget).append('|')
                    .append(duration).append('|')
                    .append(month).append('|')
                    .append(ratio).append('\n');
        }
        Map<String, Object> snapshot = Map.of(
                "campaign_ids", new ArrayList<>(campaignIds),
                "rule", SELECTION_RULE,
                "hash", sha256(canonical.toString())
        );
        TrainAcceptedResponse accepted = mlServiceClient.train(
                records,
                request == null || request.ensembleSize() == null ? 10 : request.ensembleSize(),
                request == null || request.holdoutFraction() == null
                        ? 0.2
                        : request.holdoutFraction().setScale(4, RoundingMode.HALF_UP).doubleValue(),
                snapshot);
        auditService.record(adminId, "запуск обучения", "модель", null,
                "записей " + records.size() + ", кампаний " + campaignIds.size());
        return new TrainingStartResponse(accepted.taskId());
    }

    public TrainingTaskResponse status(long adminId, String taskId) {
        TrainStatusResponse remote = mlServiceClient.trainStatus(taskId);
        TrainedModel model = remote.model();
        if ("COMPLETED".equals(remote.status()) && model != null) {
            registerModel(adminId, model);
        }
        return new TrainingTaskResponse(
                taskId,
                remote.status(),
                model == null ? null : new TrainingTaskResponse.TrainedModelInfo(
                        model.version(),
                        model.trainedAt(),
                        model.mape(),
                        model.r2(),
                        model.mae(),
                        model.holdoutSize(),
                        model.ensembleSize(),
                        model.campaignIds().size(),
                        model.campaignIds(),
                        model.selectionRule()),
                remote.error()
        );
    }

    private void registerModel(long adminId, TrainedModel model) {
        if (mlModelRepository.findByModelNameAndModelVersion(MODEL_NAME, model.version()).isPresent()) {
            return;
        }
        for (MlModel existing : mlModelRepository.findAllByOrderByTrainedAtDesc()) {
            if (Boolean.TRUE.equals(existing.getIsActive())) {
                existing.setIsActive(false);
                mlModelRepository.save(existing);
            }
        }
        MlModel entity = new MlModel();
        entity.setModelName(MODEL_NAME);
        entity.setModelVersion(model.version());
        entity.setTrainedAt(OffsetDateTime.parse(model.trainedAt()).toLocalDateTime());
        entity.setQualityMetric(BigDecimal.valueOf(model.mape()));
        entity.setIsActive(true);
        mlModelRepository.save(entity);
        auditService.record(adminId, "обучение завершено", "модель", entity.getId(),
                "версия " + model.version() + ", MAPE " + model.mape() + ", кампаний в выборке "
                        + model.campaignIds().size());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new MlServiceException("не удалось вычислить хэш датасета", exception);
        }
    }
}
