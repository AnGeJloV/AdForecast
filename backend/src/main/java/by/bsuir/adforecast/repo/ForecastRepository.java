package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.Forecast;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ForecastRepository extends JpaRepository<Forecast, Long> {

    List<Forecast> findByCampaignIdOrderByStartedAtDesc(long campaignId);

    @Query(value = "select count(*) from forecasts", nativeQuery = true)
    long countAll();

    @Query(value = "select count(*) from forecasts where status = 'COMPLETED'", nativeQuery = true)
    long countAllCompleted();

    @Query(value = """
            select count(*) from forecasts
            where campaign_id in (select campaign_id from campaign_access where user_id = :userId)
            """, nativeQuery = true)
    long countByUserId(@Param("userId") long userId);

    @Query(value = """
            select count(*) from forecasts
            where status = 'COMPLETED'
              and campaign_id in (select campaign_id from campaign_access where user_id = :userId)
            """, nativeQuery = true)
    long countCompletedByUserId(@Param("userId") long userId);
}
