package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.DailyMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface DailyMetricRepository extends JpaRepository<DailyMetric, Long> {

    long countByUploadId(Long uploadId);

    @Query("""
            select count(metric) > 0
            from DailyMetric metric
            join metric.upload upload
            where upload.campaign.id = :campaignId
              and metric.metricDate between :start and :end
            """)
    boolean existsInPeriod(@Param("campaignId") long campaignId,
                           @Param("start") LocalDate start,
                           @Param("end") LocalDate end);
}
