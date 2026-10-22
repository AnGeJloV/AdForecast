package by.bsuir.adforecast.audit;

import by.bsuir.adforecast.domain.AuditAction;
import by.bsuir.adforecast.repo.AuditActionRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

@Service
public class AuditService {

    private final AuditActionRepository auditActionRepository;

    public AuditService(AuditActionRepository auditActionRepository) {
        this.auditActionRepository = auditActionRepository;
    }

    @Transactional
    public void record(Long userId, String action, String objectType, Long objectId, String details) {
        AuditAction entry = new AuditAction();
        entry.setUserId(userId);
        entry.setAction(action);
        entry.setObjectType(objectType);
        entry.setObjectId(objectId);
        entry.setDetails(details);
        entry.setActionTime(LocalDateTime.now());
        entry.setIp(currentIp());
        auditActionRepository.save(entry);
    }

    private String currentIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String ip = request.getHeader("X-Real-IP");
        return ip == null || ip.isBlank() ? request.getRemoteAddr() : ip;
    }
}
