package by.bsuir.adforecast.campaign;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.domain.AdAccount;
import by.bsuir.adforecast.domain.Campaign;
import by.bsuir.adforecast.domain.CampaignAccess;
import by.bsuir.adforecast.domain.CampaignStatus;
import by.bsuir.adforecast.domain.User;
import by.bsuir.adforecast.forecast.CurrencyRates;
import by.bsuir.adforecast.repo.AdAccountRepository;
import by.bsuir.adforecast.repo.CampaignAccessRepository;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class CampaignService {

    private static final String METRICS_SQL = """
            select m.metric_date, m.cost, m.revenue, m.impressions, m.clicks
            from daily_metrics m
            join uploads u on u.upload_id = m.upload_id
            where u.campaign_id = ?
            order by m.metric_date
            """;

    private final CampaignRepository campaignRepository;
    private final CampaignAccessRepository campaignAccessRepository;
    private final AdAccountRepository adAccountRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    public CampaignService(CampaignRepository campaignRepository,
                           CampaignAccessRepository campaignAccessRepository,
                           AdAccountRepository adAccountRepository,
                           UserRepository userRepository,
                           AuditService auditService,
                           JdbcTemplate jdbcTemplate) {
        this.campaignRepository = campaignRepository;
        this.campaignAccessRepository = campaignAccessRepository;
        this.adAccountRepository = adAccountRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CampaignSummaryResponse> listForUser(long userId) {
        return campaignRepository.findUserSummaries(userId).stream()
                .map(CampaignSummaryResponse::from)
                .toList();
    }

    public CampaignDetailResponse get(long campaignId, long userId) {
        requireAccess(userId, campaignId);
        return buildDetail(campaignId);
    }

    /** Без проверки владения: доступ ограничивает админский контроллер. */
    @Transactional(readOnly = true)
    public CampaignDetailResponse inspect(long campaignId) {
        return buildDetail(campaignId);
    }

    @Transactional(readOnly = true)
    public List<DailyMetricPoint> inspectMetrics(long campaignId) {
        return metricsOf(campaignId);
    }

    private CampaignDetailResponse buildDetail(long campaignId) {
        Campaign campaign = campaignRepository.findWithAccount(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        CampaignSummaryProjection summary = campaignRepository.findSummary(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        ForecastStatsProjection stats = campaignRepository.findForecastStats(campaignId).orElse(null);
        AdAccount account = campaign.getAccount();
        return new CampaignDetailResponse(
                summary.getCampaignId(),
                summary.getCampaignName(),
                summary.getStatus(),
                summary.getBudget(),
                summary.getCurrency(),
                account.getId(),
                account.getAccountName(),
                summary.getPlatform(),
                summary.getUploadCount(),
                summary.getDaysCount(),
                summary.getTotalCost(),
                summary.getTotalRevenue(),
                summary.getActualRoi(),
                summary.getTotalImpressions(),
                summary.getTotalClicks(),
                stats == null ? 0L : stats.getForecastCount(),
                stats == null ? null : stats.getLastForecastAt(),
                stats == null ? null : stats.getLatestForecastRoi(),
                stats == null ? null : stats.getLatestPaybackRatio(),
                stats == null ? null : stats.getLatestVerdict()
        );
    }

    public CampaignSummaryResponse create(long userId, CampaignCreateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("пользователь не найден"));
        AdAccount account = adAccountRepository.findById(request.accountId())
                .orElseThrow(() -> new EntityNotFoundException("рекламный кабинет не найден"));
        if (!account.getOrganization().getId().equals(user.getOrganization().getId())) {
            throw new AccessDeniedException("нет доступа к рекламному кабинету");
        }
        requireSupportedCurrency(request.currency());
        Campaign campaign = new Campaign();
        campaign.setAccount(account);
        campaign.setOrganization(account.getOrganization());
        campaign.setCampaignName(request.campaignName());
        campaign.setBudget(request.budget());
        campaign.setCurrency(request.currency());
        campaign.setStatus(CampaignStatus.PLANNED);
        campaign.setInTraining(true);
        campaign.setIsTest(false);
        campaign.setCreatedAt(LocalDateTime.now());
        campaign = campaignRepository.save(campaign);

        CampaignAccess access = new CampaignAccess();
        access.setUser(user);
        access.setCampaign(campaign);
        campaignAccessRepository.save(access);

        auditService.record(userId, "создание кампании", "кампания", campaign.getId(),
                "«" + campaign.getCampaignName() + "», " + account.getPlatform()
                        + ", бюджет " + campaign.getBudget().toPlainString() + " " + campaign.getCurrency()
                        + ", статус «планируется»");
        return findSummary(campaign.getId());
    }

    public CampaignSummaryResponse update(long campaignId, long userId, CampaignUpdateRequest request) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        requireAccess(userId, campaignId);
        requireSupportedCurrency(request.currency());
        String previousName = campaign.getCampaignName();
        BigDecimal previousBudget = campaign.getBudget();
        String previousCurrency = campaign.getCurrency();
        CampaignStatus previousStatus = campaign.getStatus();

        campaign.setCampaignName(request.campaignName());
        campaign.setBudget(request.budget());
        campaign.setCurrency(request.currency());
        campaign.setStatus(request.status());
        campaignRepository.save(campaign);

        List<String> changes = new ArrayList<>();
        if (!previousName.equals(request.campaignName())) {
            changes.add("название «" + previousName + "» → «" + request.campaignName() + "»");
        }
        if (previousBudget.compareTo(request.budget()) != 0) {
            changes.add("бюджет " + previousBudget.toPlainString() + " → " + request.budget().toPlainString()
                    + " " + request.currency());
        }
        if (!previousCurrency.equals(request.currency())) {
            changes.add("валюта " + previousCurrency + " → " + request.currency());
        }
        if (previousStatus != request.status()) {
            changes.add("статус «" + statusLabel(previousStatus) + "» → «" + statusLabel(request.status()) + "»");
        }
        if (!changes.isEmpty()) {
            auditService.record(userId, "изменение кампании", "кампания", campaignId,
                    "«" + request.campaignName() + "»: " + String.join(", ", changes));
        }
        if (previousStatus != CampaignStatus.FINISHED && request.status() == CampaignStatus.FINISHED) {
            CampaignSummaryResponse summary = findSummary(campaignId);
            String roi = summary.actualRoi() == null
                    ? ""
                    : ", окупаемость " + ratioText(summary.actualRoi());
            auditService.record(userId, "завершение кампании", "кампания", campaignId,
                    "«" + request.campaignName() + "»: расход " + summary.totalCost().toPlainString()
                            + ", доход " + summary.totalRevenue().toPlainString() + roi);
        }
        return findSummary(campaignId);
    }

    /** Модель считает в BYN, курсы НБРБ известны только для этих валют. */
    private static void requireSupportedCurrency(String currency) {
        if (!CurrencyRates.isSupported(currency)) {
            throw new IllegalArgumentException("валюта «" + currency + "» не поддерживается — доступны "
                    + String.join(", ", CurrencyRates.supported()));
        }
    }

    private static String statusLabel(CampaignStatus status) {
        return switch (status) {
            case PLANNED -> "планируется";
            case ACTIVE -> "активна";
            case FINISHED -> "завершена";
        };
    }

    /** Фактическая окупаемость из процентов API в вид «1,24 на каждый вложенный». */
    private static String ratioText(BigDecimal roiPercent) {
        return BigDecimal.ONE.add(roiPercent.movePointLeft(2)).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @Transactional(readOnly = true)
    public List<DailyMetricPoint> metrics(long campaignId, long userId) {
        campaignRepository.findById(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        requireAccess(userId, campaignId);
        return metricsOf(campaignId);
    }

    private List<DailyMetricPoint> metricsOf(long campaignId) {
        return jdbcTemplate.query(METRICS_SQL, (rs, rowNum) -> new DailyMetricPoint(
                rs.getDate("metric_date").toLocalDate(),
                rs.getBigDecimal("cost"),
                rs.getBigDecimal("revenue"),
                rs.getInt("impressions"),
                rs.getInt("clicks")
        ), campaignId);
    }

    public void requireAccess(long userId, long campaignId) {
        if (!campaignAccessRepository.existsByUserIdAndCampaignId(userId, campaignId)) {
            throw new AccessDeniedException("нет доступа к кампании");
        }
    }

    private CampaignSummaryResponse findSummary(long campaignId) {
        return campaignRepository.findSummary(campaignId)
                .map(CampaignSummaryResponse::from)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
    }
}
