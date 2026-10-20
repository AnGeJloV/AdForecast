package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.ForecastResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ForecastResultRepository extends JpaRepository<ForecastResult, Long> {

    Optional<ForecastResult> findByForecastId(Long forecastId);
}
