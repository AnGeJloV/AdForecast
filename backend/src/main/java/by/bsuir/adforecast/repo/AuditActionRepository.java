package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.AuditAction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditActionRepository extends JpaRepository<AuditAction, Long> {
}
