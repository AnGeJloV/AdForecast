package by.bsuir.adforecast.upload;

import by.bsuir.adforecast.campaign.CampaignService;
import by.bsuir.adforecast.domain.Campaign;
import by.bsuir.adforecast.domain.DailyMetric;
import by.bsuir.adforecast.domain.Upload;
import by.bsuir.adforecast.domain.UploadFormat;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.DailyMetricRepository;
import by.bsuir.adforecast.repo.UploadRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@Transactional
public class UploadService {

    private final CampaignRepository campaignRepository;
    private final CampaignService campaignService;
    private final UploadRepository uploadRepository;
    private final DailyMetricRepository dailyMetricRepository;
    private final StatsParser statsParser;

    public UploadService(CampaignRepository campaignRepository,
                         CampaignService campaignService,
                         UploadRepository uploadRepository,
                         DailyMetricRepository dailyMetricRepository,
                         StatsParser statsParser) {
        this.campaignRepository = campaignRepository;
        this.campaignService = campaignService;
        this.uploadRepository = uploadRepository;
        this.dailyMetricRepository = dailyMetricRepository;
        this.statsParser = statsParser;
    }

    public UploadResponse upload(long campaignId, boolean admin, long userId, MultipartFile file) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        if (!admin) {
            campaignService.requireAccess(userId, campaignId);
        }
        if (file.isEmpty()) {
            throw new UploadException(List.of("файл пуст"));
        }
        UploadFormat format;
        ParsedRows parsed;
        try {
            format = statsParser.detectFormat(file);
            parsed = statsParser.parse(format, file.getInputStream());
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
        return UploadResponse.of(upload, metrics.size());
    }

    public List<UploadResponse> history(long campaignId) {
        return uploadRepository.findByCampaignIdOrderByIdDesc(campaignId).stream()
                .map(upload -> UploadResponse.of(upload, dailyMetricRepository.countByUploadId(upload.getId())))
                .toList();
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
