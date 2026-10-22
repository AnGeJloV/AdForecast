package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.Forecast;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ForecastRepository extends JpaRepository<Forecast, Long> {

    List<Forecast> findByCampaignIdOrderByStartedAtDesc(long campaignId);

    @Query("""
            select forecast
            from Forecast forecast
            join fetch forecast.model
            join fetch forecast.campaign
            where forecast.id = :id
            """)
    Optional<Forecast> findByIdWithModel(@Param("id") long id);

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
