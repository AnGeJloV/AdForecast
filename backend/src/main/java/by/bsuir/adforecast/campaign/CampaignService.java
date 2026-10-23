package by.bsuir.adforecast.campaign;

import by.bsuir.adforecast.domain.AdAccount;
import by.bsuir.adforecast.domain.Campaign;
import by.bsuir.adforecast.domain.CampaignAccess;
import by.bsuir.adforecast.domain.CampaignStatus;
import by.bsuir.adforecast.repo.AdAccountRepository;
import by.bsuir.adforecast.repo.CampaignAccessRepository;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
    private final JdbcTemplate jdbcTemplate;

    public CampaignService(CampaignRepository campaignRepository,
                           CampaignAccessRepository campaignAccessRepository,
                           AdAccountRepository adAccountRepository,
                           UserRepository userRepository,
                           JdbcTemplate jdbcTemplate) {
        this.campaignRepository = campaignRepository;
        this.campaignAccessRepository = campaignAccessRepository;
        this.adAccountRepository = adAccountRepository;
        this.userRepository = userRepository;
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
                stats == null ? null : stats.getLatestPaybackMonths(),
                stats == null ? null : stats.getLatestPaybackProbability()
        );
    }

    public CampaignSummaryResponse create(long userId, CampaignCreateRequest request) {
        AdAccount account = adAccountRepository.findById(request.accountId())
                .orElseThrow(() -> new EntityNotFoundException("рекламный кабинет не найден"));
        Campaign campaign = new Campaign();
        campaign.setAccount(account);
        campaign.setCampaignName(request.campaignName());
        campaign.setBudget(request.budget());
        campaign.setCurrency(request.currency());
        campaign.setStatus(CampaignStatus.PLANNED);
        campaign.setInTraining(true);
        campaign.setIsTest(false);
        campaign.setCreatedAt(LocalDateTime.now());
        campaign = campaignRepository.save(campaign);

        CampaignAccess access = new CampaignAccess();
        access.setUser(userRepository.getReferenceById(userId));
        access.setCampaign(campaign);
        campaignAccessRepository.save(access);

        return findSummary(campaign.getId());
    }

    public CampaignSummaryResponse update(long campaignId, long userId, CampaignUpdateRequest request) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        requireAccess(userId, campaignId);
        campaign.setCampaignName(request.campaignName());
        campaign.setBudget(request.budget());
        campaign.setCurrency(request.currency());
        campaign.setStatus(request.status());
        campaignRepository.save(campaign);
        return findSummary(campaignId);
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
