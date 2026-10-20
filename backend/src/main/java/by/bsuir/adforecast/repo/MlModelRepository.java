package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.MlModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MlModelRepository extends JpaRepository<MlModel, Long> {

    Optional<MlModel> findByModelNameAndModelVersion(String modelName, String modelVersion);
}
