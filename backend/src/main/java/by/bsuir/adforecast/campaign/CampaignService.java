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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final CampaignAccessRepository campaignAccessRepository;
    private final AdAccountRepository adAccountRepository;
    private final UserRepository userRepository;

    public CampaignService(CampaignRepository campaignRepository,
                           CampaignAccessRepository campaignAccessRepository,
                           AdAccountRepository adAccountRepository,
                           UserRepository userRepository) {
        this.campaignRepository = campaignRepository;
        this.campaignAccessRepository = campaignAccessRepository;
        this.adAccountRepository = adAccountRepository;
        this.userRepository = userRepository;
    }

    public List<CampaignSummaryResponse> listAll() {
        return campaignRepository.findAllSummaries().stream()
                .map(CampaignSummaryResponse::from)
                .toList();
    }

    public List<CampaignSummaryResponse> listForUser(long userId) {
        return campaignRepository.findUserSummaries(userId).stream()
                .map(CampaignSummaryResponse::from)
                .toList();
    }

    public CampaignDetailResponse get(long campaignId, boolean admin, long userId) {
        Campaign campaign = campaignRepository.findWithAccount(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        if (!admin) {
            requireAccess(userId, campaignId);
        }
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
        campaign.setCreatedAt(LocalDateTime.now());
        campaign = campaignRepository.save(campaign);

        CampaignAccess access = new CampaignAccess();
        access.setUser(userRepository.getReferenceById(userId));
        access.setCampaign(campaign);
        campaignAccessRepository.save(access);

        return findSummary(campaign.getId());
    }

    public CampaignSummaryResponse update(long campaignId, boolean admin, long userId, CampaignUpdateRequest request) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        if (!admin) {
            requireAccess(userId, campaignId);
        }
        campaign.setCampaignName(request.campaignName());
        campaign.setBudget(request.budget());
        campaign.setCurrency(request.currency());
        campaign.setStatus(request.status());
        campaignRepository.save(campaign);
        return findSummary(campaignId);
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
