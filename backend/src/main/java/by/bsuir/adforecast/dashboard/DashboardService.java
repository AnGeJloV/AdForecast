package by.bsuir.adforecast.dashboard;

import by.bsuir.adforecast.campaign.CampaignSummaryProjection;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.ForecastRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@Transactional
public class DashboardService {

    private final CampaignRepository campaignRepository;
    private final ForecastRepository forecastRepository;

    public DashboardService(CampaignRepository campaignRepository, ForecastRepository forecastRepository) {
        this.campaignRepository = campaignRepository;
        this.forecastRepository = forecastRepository;
    }

    public DashboardResponse build(boolean admin, long userId) {
        List<CampaignSummaryProjection> summaries =
                admin ? campaignRepository.findAllSummaries() : campaignRepository.findUserSummaries(userId);
        long total = summaries.size();
        long planned = summaries.stream().filter(s -> "PLANNED".equals(s.getStatus())).count();
        long active = summaries.stream().filter(s -> "ACTIVE".equals(s.getStatus())).count();
        long finished = total - planned - active;

        BigDecimal totalBudget = summaries.stream()
                .map(CampaignSummaryProjection::getBudget)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCost = summaries.stream()
                .map(CampaignSummaryProjection::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRevenue = summaries.stream()
                .map(CampaignSummaryProjection::getTotalRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actualRoi = totalCost.signum() == 0
                ? null
                : totalRevenue.subtract(totalCost)
                        .divide(totalCost, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

        long profitable = summaries.stream()
                .filter(summary -> "FINISHED".equals(summary.getStatus()))
                .map(CampaignSummaryProjection::getActualRoi)
                .filter(roi -> roi != null && roi.signum() > 0)
                .count();
        long unprofitable = finished - profitable;

        long forecastsTotal = admin ? forecastRepository.countAll() : forecastRepository.countByUserId(userId);
        long forecastsCompleted = admin
                ? forecastRepository.countAllCompleted()
                : forecastRepository.countCompletedByUserId(userId);

        return new DashboardResponse(
                total, planned, active, finished,
                totalBudget, totalCost, totalRevenue, actualRoi,
                profitable, unprofitable,
                forecastsTotal, forecastsCompleted
        );
    }
}
