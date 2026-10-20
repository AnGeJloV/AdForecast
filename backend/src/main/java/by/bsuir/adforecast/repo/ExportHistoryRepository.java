package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.ExportHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExportHistoryRepository extends JpaRepository<ExportHistory, Long> {
}
