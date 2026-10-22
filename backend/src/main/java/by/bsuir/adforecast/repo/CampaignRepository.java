package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.campaign.CampaignSummaryProjection;
import by.bsuir.adforecast.campaign.ForecastStatsProjection;
import by.bsuir.adforecast.domain.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

import static by.bsuir.adforecast.repo.Queries.CAMPAIGN_SUMMARY_SELECT;
import static by.bsuir.adforecast.repo.Queries.FORECAST_STATS_SELECT;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    @Query(value = CAMPAIGN_SUMMARY_SELECT + """
            where v.campaign_id in (select campaign_id from campaign_access where user_id = :userId)
            order by v.campaign_id desc
            """, nativeQuery = true)
    List<CampaignSummaryProjection> findUserSummaries(@Param("userId") long userId);

    @Query(value = CAMPAIGN_SUMMARY_SELECT + " where v.campaign_id = :campaignId", nativeQuery = true)
    Optional<CampaignSummaryProjection> findSummary(@Param("campaignId") long campaignId);

    @Query(value = FORECAST_STATS_SELECT + " where v.campaign_id = :campaignId", nativeQuery = true)
    Optional<ForecastStatsProjection> findForecastStats(@Param("campaignId") long campaignId);

    @Query("""
            select campaign
            from Campaign campaign
            join fetch campaign.account
            where campaign.id = :id
            """)
    Optional<Campaign> findWithAccount(@Param("id") long id);
}
