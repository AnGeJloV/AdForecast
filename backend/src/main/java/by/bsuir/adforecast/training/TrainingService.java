package by.bsuir.adforecast.training;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.domain.Campaign;
import by.bsuir.adforecast.domain.Organization;
import by.bsuir.adforecast.mlclient.TrainAcceptedResponse;
import by.bsuir.adforecast.mlclient.TrainStatusResponse;
import by.bsuir.adforecast.mlclient.TrainedModel;
import by.bsuir.adforecast.mlclient.MlServiceClient;
import by.bsuir.adforecast.mlclient.MlServiceException;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.MlModelRepository;
import by.bsuir.adforecast.repo.OrganizationRepository;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class TrainingService {

    /** Минимум записей и кампаний для запуска обучения (совпадает с контрактом ml-service). */
    static final int MIN_RECORDS = 40;
    static final int MIN_CAMPAIGNS = 10;
    static final String GENERAL_MODEL_NAME = "adforecast_roi";

    private static final double[] CUTOFF_FRACTIONS = {0.0, 0.3, 0.55, 0.8};

    private static final String METRICS_SQL = """
            SELECT c.campaign_id AS campaign_id,
                   a.platform AS platform,
                   c.budget AS budget,
                   m.metric_date AS metric_date,
                   m.cost AS cost,
                   m.revenue AS revenue
            FROM campaigns c
            JOIN ad_accounts a ON a.account_id = c.account_id
            JOIN uploads u ON u.campaign_id = c.campaign_id
            JOIN daily_metrics m ON m.upload_id = u.upload_id
            WHERE c.status = 'FINISHED' AND c.in_training AND NOT c.is_test
            ORDER BY c.campaign_id, m.metric_date
            """;

    private static final String SIGNATURE_SQL = """
            SELECT count(DISTINCT c.campaign_id) AS campaigns,
                   count(m.metric_id) AS days,
                   max(m.metric_date) AS last_day
            FROM campaigns c
            JOIN uploads u ON u.campaign_id = c.campaign_id
            JOIN daily_metrics m ON m.upload_id = u.upload_id
            WHERE c.status = 'FINISHED' AND c.in_training AND NOT c.is_test
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
                   c.is_test AS is_test,
                   (SELECT count(DISTINCT m.metric_date)
                    FROM uploads u
                    JOIN daily_metrics m ON m.upload_id = u.upload_id
                    WHERE u.campaign_id = c.campaign_id) AS days,
                   (SELECT min(u.period_start) FROM uploads u
                    WHERE u.campaign_id = c.campaign_id) AS period_start,
                   (SELECT max(u.period_end) FROM uploads u
                    WHERE u.campaign_id = c.campaign_id) AS period_end
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

    private static final String ORG_FILTER = " AND c.organization_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final CampaignRepository campaignRepository;
    private final OrganizationRepository organizationRepository;
    private final MlModelRepository mlModelRepository;
    private final MlServiceClient mlServiceClient;
    private final AuditService auditService;

    /** Подпись данных, на которой обучается конкретная задача (для регистрации версии). */
    private final Map<String, String> taskSignatures = new ConcurrentHashMap<>();

    public TrainingService(JdbcTemplate jdbcTemplate,
                           CampaignRepository campaignRepository,
                           OrganizationRepository organizationRepository,
                           MlModelRepository mlModelRepository,
                           MlServiceClient mlServiceClient,
                           AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.campaignRepository = campaignRepository;
        this.organizationRepository = organizationRepository;
        this.mlModelRepository = mlModelRepository;
        this.mlServiceClient = mlServiceClient;
        this.auditService = auditService;
    }

    /** Датасет отсечек с моделью и снимком — то, что уходит в ml-service на обучение. */
    public record TrainingPayload(List<Map<String, Object>> records, String modelName,
                                   Map<String, Object> snapshot, int campaignCount, String signature) {
    }

    @Transactional(readOnly = true)
    public TrainingPreviewResponse preview(String scope, Long organizationId) {
        Organization organization = resolveScope(scope, organizationId);
        List<CutoffRecord> records = cutoffRecords(organization);
        Map<Long, List<CutoffRecord>> byCampaign = records.stream()
                .collect(Collectors.groupingBy(CutoffRecord::campaignId, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> included = jdbcTemplate.queryForList(
                organization == null ? INCLUDED_SQL : INCLUDED_SQL.replace(" GROUP BY", ORG_FILTER + " GROUP BY"),
                organization == null ? new Object[0] : new Object[]{organization.getId()});
        List<Map<String, Object>> excluded = jdbcTemplate.queryForList(
                organization == null ? EXCLUDED_SQL : EXCLUDED_SQL.replace(" ORDER BY", ORG_FILTER + " ORDER BY"),
                organization == null ? new Object[0] : new Object[]{organization.getId()});

        Map<String, Long> recordsByPlatform = records.stream()
                .collect(Collectors.groupingBy(CutoffRecord::platform, Collectors.counting()));
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
                byCampaign.size(),
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
                        String.valueOf(row.get("ratio")),
                        text(row.get("period_start")),
                        text(row.get("period_end")))).toList(),
                excluded.stream().map(row -> new TrainingPreviewResponse.ExcludedCampaign(
                        ((Number) row.get("campaign_id")).longValue(),
                        String.valueOf(row.get("campaign_name")),
                        String.valueOf(row.get("platform")),
                        Boolean.TRUE.equals(row.get("in_training")),
                        Boolean.TRUE.equals(row.get("is_test")),
                        ((Number) row.get("days")).longValue(),
                        text(row.get("period_start")),
                        text(row.get("period_end")))).toList()
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
        Organization organization = resolveScope(
                request == null || request.scope() == null ? "GENERAL" : request.scope(),
                request == null ? null : request.organizationId());
        TrainingPayload payload = buildPayload(organization);
        TrainAcceptedResponse accepted = submit(
                payload,
                request == null ? null : request.ensembleSize(),
                request == null || request.holdoutFraction() == null
                        ? null
                        : request.holdoutFraction().setScale(4, RoundingMode.HALF_UP).doubleValue());
        auditService.record(adminId, "запуск обучения", "модель", null,
                "записей " + payload.records().size() + ", кампаний " + payload.campaignCount()
                        + ", область: " + (organization == null ? "общая" : organization.getName()));
        return new TrainingStartResponse(accepted.taskId());
    }

    public TrainAcceptedResponse submit(TrainingPayload payload, Integer ensembleSize, Double holdoutFraction) {
        TrainAcceptedResponse accepted = mlServiceClient.train(
                payload.records(),
                ensembleSize == null ? 10 : ensembleSize,
                holdoutFraction == null ? 0.2 : holdoutFraction,
                payload.modelName(),
                payload.snapshot());
        taskSignatures.put(accepted.taskId(), payload.signature());
        return accepted;
    }

    public TrainingPayload buildPayload(Organization organization) {
        List<CutoffRecord> dataset = cutoffRecords(organization);
        if (dataset.size() < MIN_RECORDS) {
            throw new IllegalArgumentException(
                    "недостаточно данных для обучения: " + dataset.size() + " записей (минимум " + MIN_RECORDS + ")");
        }
        long campaignCount = dataset.stream().map(CutoffRecord::campaignId).distinct().count();
        if (campaignCount < MIN_CAMPAIGNS) {
            throw new IllegalArgumentException(
                    "недостаточно кампаний для обучения: " + campaignCount + " (минимум " + MIN_CAMPAIGNS + ")");
        }
        List<Map<String, Object>> records = new ArrayList<>(dataset.size());
        TreeSet<Long> campaignIds = new TreeSet<>();
        StringBuilder canonical = new StringBuilder();
        for (CutoffRecord record : dataset) {
            records.add(record.toMap());
            campaignIds.add(record.campaignId());
            canonical.append(record.canonical()).append('\n');
        }
        String modelName = organization == null ? GENERAL_MODEL_NAME : organizationModelName(organization.getId());
        Map<String, Object> snapshot = Map.of(
                "campaign_ids", new ArrayList<>(campaignIds),
                "rule", selectionRule(organization),
                "hash", sha256(canonical.toString())
        );
        return new TrainingPayload(records, modelName, snapshot, campaignIds.size(),
                datasetSignature(organization));
    }

    /** Подпись обучающих данных: меняется, когда появляются новые завершённые кампании. */
    public String datasetSignature(Organization organization) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                organization == null ? SIGNATURE_SQL : SIGNATURE_SQL + ORG_FILTER,
                organization == null ? new Object[0] : new Object[]{organization.getId()});
        return "campaigns=" + row.get("campaigns") + ";days=" + row.get("days") + ";last=" + row.get("last_day");
    }

    public TrainingTaskResponse status(long adminId, String taskId) {
        TrainStatusResponse remote = mlServiceClient.trainStatus(taskId);
        TrainedModel model = remote.model();
        if ("COMPLETED".equals(remote.status()) && model != null) {
            registerModel(adminId, model, taskSignatures.remove(taskId));
        }
        return toTaskResponse(taskId, remote);
    }

    TrainingTaskResponse toTaskResponse(String taskId, TrainStatusResponse remote) {
        TrainedModel model = remote.model();
        return new TrainingTaskResponse(
                taskId,
                remote.status(),
                model == null ? null : new TrainingTaskResponse.TrainedModelInfo(
                        model.version(),
                        model.trainedAt(),
                        model.mape(),
                        model.r2(),
                        model.mae(),
                        model.mapeWithHistory(),
                        model.r2WithHistory(),
                        model.mapePlanned(),
                        model.r2Planned(),
                        model.holdoutSize(),
                        model.ensembleSize(),
                        model.campaignIds().size(),
                        model.campaignIds(),
                        model.selectionRule()),
                remote.error()
        );
    }

    @Transactional
    public MlModel registerModel(Long actorId, TrainedModel model, String signature) {
        MlModel registered = mlModelRepository.findByModelNameAndModelVersion(model.modelName(), model.version())
                .orElseGet(MlModel::new);
        if (registered.getId() != null && Boolean.TRUE.equals(registered.getIsActive())) {
            return registered;
        }
        Organization organization = model.modelName().startsWith("adforecast_org_")
                ? organizationRepository.findById(Long.parseLong(model.modelName().substring("adforecast_org_".length())))
                        .orElse(null)
                : null;
        deactivateScope(organization);
        registered.setModelName(model.modelName());
        registered.setModelVersion(model.version());
        registered.setOrganization(organization);
        registered.setTrainedAt(OffsetDateTime.parse(model.trainedAt()).toLocalDateTime());
        registered.setQualityMetric(BigDecimal.valueOf(model.mape()));
        registered.setTrainingSignature(signature);
        registered.setIsActive(true);
        MlModel saved = mlModelRepository.save(registered);
        auditService.record(actorId,
                organization == null ? "обучение завершено" : "обучение модели организации",
                "модель", saved.getId(),
                "версия " + model.version() + ", MAPE " + model.mape()
                        + (model.mapeWithHistory() == null ? "" : ", MAPE с историей " + model.mapeWithHistory())
                        + ", кампаний в выборке " + model.campaignIds().size()
                        + (organization == null ? "" : ", организация " + organization.getName()));
        return saved;
    }

    private void deactivateScope(Organization organization) {
        List<MlModel> scope = organization == null
                ? mlModelRepository.findByOrganizationIdIsNull()
                : mlModelRepository.findByOrganizationId(organization.getId());
        for (MlModel model : scope) {
            if (Boolean.TRUE.equals(model.getIsActive())) {
                model.setIsActive(false);
                mlModelRepository.save(model);
            }
        }
    }

    Organization resolveScope(String scope, Long organizationId) {
        if ("ORGANIZATION".equals(scope)) {
            if (organizationId == null) {
                throw new IllegalArgumentException("укажите организацию для обучения своей модели");
            }
            return organizationRepository.findById(organizationId)
                    .orElseThrow(() -> new EntityNotFoundException("организация не найдена"));
        }
        return null;
    }

    private List<CutoffRecord> cutoffRecords(Organization organization) {
        String sql = organization == null ? METRICS_SQL
                : METRICS_SQL.replace("WHERE c.status = 'FINISHED'",
                        "WHERE c.organization_id = " + organization.getId() + " AND c.status = 'FINISHED'");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        List<CutoffRecord> records = new ArrayList<>();
        Map<Long, List<Map<String, Object>>> byCampaign = rows.stream()
                .collect(Collectors.groupingBy(row -> ((Number) row.get("campaign_id")).longValue(),
                        LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<Long, List<Map<String, Object>>> entry : byCampaign.entrySet()) {
            List<Map<String, Object>> days = entry.getValue();
            double totalCost = days.stream().mapToDouble(row -> ((Number) row.get("cost")).doubleValue()).sum();
            double totalRevenue = days.stream().mapToDouble(row -> ((Number) row.get("revenue")).doubleValue()).sum();
            if (totalCost <= 0) {
                continue;
            }
            long campaignId = entry.getKey();
            String platform = String.valueOf(days.get(0).get("platform"));
            double budget = ((BigDecimal) days.get(0).get("budget")).doubleValue();
            int duration = days.size();
            int startMonth = ((java.sql.Date) days.get(0).get("metric_date")).toLocalDate().getMonthValue();
            double target = totalRevenue / totalCost;

            TreeSet<Integer> cutoffs = new TreeSet<>();
            for (double fraction : CUTOFF_FRACTIONS) {
                cutoffs.add((int) Math.round(duration * fraction));
            }
            for (int cutoff : cutoffs) {
                int index = Math.min(cutoff, duration);
                double cost = 0.0;
                double revenue = 0.0;
                for (int i = 0; i < index; i++) {
                    cost += ((Number) days.get(i).get("cost")).doubleValue();
                    revenue += ((Number) days.get(i).get("revenue")).doubleValue();
                }
                double cost7 = 0.0;
                double revenue7 = 0.0;
                for (int i = Math.max(0, index - 7); i < index; i++) {
                    cost7 += ((Number) days.get(i).get("cost")).doubleValue();
                    revenue7 += ((Number) days.get(i).get("revenue")).doubleValue();
                }
                records.add(new CutoffRecord(campaignId, platform, budget, duration, startMonth, index,
                        cost, revenue, cost7, revenue7, target));
            }
        }
        return records;
    }

    private String selectionRule(Organization organization) {
        String base = "FINISHED AND in_training AND NOT is_test AND SUM(cost) > 0, отсечки 0/30/55/80 %";
        return organization == null ? "все организации: " + base : "организация " + organization.getName() + ": " + base;
    }

    static String organizationModelName(long organizationId) {
        return "adforecast_org_" + organizationId;
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

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record CutoffRecord(long campaignId, String platform, double budget, int durationDays, int startMonth,
                                int cutoffDays, double costToDate, double revenueToDate,
                                double costLast7, double revenueLast7, double targetRatio) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("campaign_id", campaignId);
            map.put("platform", platform);
            map.put("budget", round(budget));
            map.put("duration_days", durationDays);
            map.put("start_month", startMonth);
            map.put("cutoff_days", cutoffDays);
            map.put("cost_to_date", round(costToDate));
            map.put("revenue_to_date", round(revenueToDate));
            map.put("cost_last7", round(costLast7));
            map.put("revenue_last7", round(revenueLast7));
            map.put("target_ratio", round(targetRatio));
            return map;
        }

        String canonical() {
            return campaignId + "|" + platform + "|" + round(budget) + "|" + durationDays + "|" + startMonth
                    + "|" + cutoffDays + "|" + round(costToDate) + "|" + round(revenueToDate)
                    + "|" + round(costLast7) + "|" + round(revenueLast7) + "|" + round(targetRatio);
        }

        private static double round(double value) {
            return Math.round(value * 10000.0) / 10000.0;
        }
    }
}
