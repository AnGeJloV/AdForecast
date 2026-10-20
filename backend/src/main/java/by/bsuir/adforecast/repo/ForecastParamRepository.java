package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.ForecastParam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ForecastParamRepository extends JpaRepository<ForecastParam, Long> {

    Optional<ForecastParam> findByForecastId(Long forecastId);
}
