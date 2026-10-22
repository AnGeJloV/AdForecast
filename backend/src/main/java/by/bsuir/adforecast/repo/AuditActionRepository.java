package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.admin.AuditEntryResponse;
import by.bsuir.adforecast.domain.AuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AuditActionRepository extends JpaRepository<AuditAction, Long> {

    Page<AuditAction> findAllByOrderByIdDesc(Pageable pageable);

    @Query("""
            select new by.bsuir.adforecast.admin.AuditEntryResponse(
                action.id, action.userId, user.login, user.fullName,
                action.action, action.objectType, action.objectId, action.details, action.actionTime, action.ip)
            from AuditAction action
            left join User user on user.id = action.userId
            order by action.id desc
            """)
    Page<AuditEntryResponse> findEntries(Pageable pageable);
}
