package by.bsuir.adforecast.upload;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.campaign.CampaignService;
import by.bsuir.adforecast.domain.Campaign;
import by.bsuir.adforecast.domain.DailyMetric;
import by.bsuir.adforecast.domain.Upload;
import by.bsuir.adforecast.domain.UploadFormat;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.DailyMetricRepository;
import by.bsuir.adforecast.repo.UploadRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class UploadService {

    private static final String HISTORY_ALL_SQL = """
            select u.upload_id, u.campaign_id, c.campaign_name, u.file_name, u.file_format,
                   u.period_start, u.period_end, u.uploaded_at,
                   (select count(*) from daily_metrics m where m.upload_id = u.upload_id) as rows_count
            from uploads u
            join campaigns c on c.campaign_id = u.campaign_id
            where u.campaign_id in (select campaign_id from campaign_access where user_id = ?)
            order by u.uploaded_at desc
            limit 100
            """;

    private final CampaignRepository campaignRepository;
    private final CampaignService campaignService;
    private final UploadRepository uploadRepository;
    private final DailyMetricRepository dailyMetricRepository;
    private final StatsParser statsParser;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    public UploadService(CampaignRepository campaignRepository,
                         CampaignService campaignService,
                         UploadRepository uploadRepository,
                         DailyMetricRepository dailyMetricRepository,
                         StatsParser statsParser,
                         AuditService auditService,
                         JdbcTemplate jdbcTemplate) {
        this.campaignRepository = campaignRepository;
        this.campaignService = campaignService;
        this.uploadRepository = uploadRepository;
        this.dailyMetricRepository = dailyMetricRepository;
        this.statsParser = statsParser;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public AnalyzeResponse analyze(long campaignId, long userId, MultipartFile file) {
        requireCampaignAccess(campaignId, userId);
        if (file.isEmpty()) {
            throw new UploadException(List.of("файл пуст"));
        }
        try {
            UploadFormat format = statsParser.detectFormat(file);
            AnalyzeResponse analysis = statsParser.analyze(format, file.getInputStream());
            if (analysis.periodStart() != null
                    && dailyMetricRepository.existsInPeriod(campaignId, analysis.periodStart(), analysis.periodEnd())) {
                List<String> warnings = new ArrayList<>(analysis.warnings());
                warnings.add("период выгрузки пересекается с ранее загруженными данными кампании");
                return withWarnings(analysis, warnings);
            }
            return analysis;
        } catch (IOException exception) {
            throw new UploadException(List.of("не удалось прочитать файл"));
        }
    }

    public UploadResponse upload(long campaignId, long userId, MultipartFile file,
                                 Map<String, Integer> mapping) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        campaignService.requireAccess(userId, campaignId);
        if (file.isEmpty()) {
            throw new UploadException(List.of("файл пуст"));
        }
        UploadFormat format;
        ParsedRows parsed;
        try {
            format = statsParser.detectFormat(file);
            parsed = statsParser.parse(format, file.getInputStream(), mapping);
        } catch (IOException exception) {
            throw new UploadException(List.of("не удалось прочитать файл"));
        }
        if (!parsed.errors().isEmpty()) {
            throw new UploadException(parsed.errors());
        }
        List<MetricRow> rows = parsed.rows();
        if (rows.isEmpty()) {
            throw new UploadException(List.of("файл не содержит строк с показателями"));
        }
        LocalDate start = rows.stream().map(MetricRow::date).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate end = rows.stream().map(MetricRow::date).max(Comparator.naturalOrder()).orElseThrow();
        if (dailyMetricRepository.existsInPeriod(campaignId, start, end)) {
            throw new UploadException(List.of("период выгрузки пересекается с ранее загруженными данными кампании"));
        }

        Upload upload = new Upload();
        upload.setCampaign(campaign);
        upload.setFileName(resolveFileName(file, format));
        upload.setFileFormat(format);
        upload.setPeriodStart(start);
        upload.setPeriodEnd(end);
        upload.setUploadedAt(LocalDateTime.now());
        upload = uploadRepository.save(upload);

        List<DailyMetric> metrics = new ArrayList<>(rows.size());
        for (MetricRow row : rows) {
            DailyMetric metric = new DailyMetric();
            metric.setUpload(upload);
            metric.setMetricDate(row.date());
            metric.setCost(row.cost());
            metric.setRevenue(row.revenue());
            metric.setImpressions(row.impressions());
            metric.setClicks(row.clicks());
            metrics.add(metric);
        }
        dailyMetricRepository.saveAll(metrics);
        auditService.record(userId, "загрузка показателей", "кампания", campaignId,
                "файл " + upload.getFileName() + ", строк " + metrics.size());
        return UploadResponse.of(upload, metrics.size());
    }

    @Transactional(readOnly = true)
    public List<UploadResponse> history(long campaignId, long userId) {
        requireCampaignAccess(campaignId, userId);
        return uploadRepository.findByCampaignIdOrderByIdDesc(campaignId).stream()
                .map(upload -> UploadResponse.of(upload, dailyMetricRepository.countByUploadId(upload.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UploadListItem> historyAll(long userId) {
        return jdbcTemplate.query(HISTORY_ALL_SQL, (rs, rowNum) -> new UploadListItem(
                rs.getLong("upload_id"),
                rs.getLong("campaign_id"),
                rs.getString("campaign_name"),
                rs.getString("file_name"),
                rs.getString("file_format"),
                rs.getDate("period_start").toLocalDate(),
                rs.getDate("period_end").toLocalDate(),
                rs.getTimestamp("uploaded_at").toLocalDateTime(),
                rs.getLong("rows_count")
        ), userId);
    }

    private void requireCampaignAccess(long campaignId, long userId) {
        campaignRepository.findById(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        campaignService.requireAccess(userId, campaignId);
    }

    private AnalyzeResponse withWarnings(AnalyzeResponse analysis, List<String> warnings) {
        return new AnalyzeResponse(
                analysis.format(), analysis.delimiter(), analysis.charset(), analysis.dateFormat(),
                analysis.columns(), analysis.previewRows(), analysis.rowCount(),
                analysis.periodStart(), analysis.periodEnd(), warnings, analysis.errors());
    }

    private String resolveFileName(MultipartFile file, UploadFormat format) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return "upload." + format.name().toLowerCase();
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
}
