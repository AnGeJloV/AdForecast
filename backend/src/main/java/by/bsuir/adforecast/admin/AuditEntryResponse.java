package by.bsuir.adforecast.admin;

import java.time.LocalDateTime;

public record AuditEntryResponse(
        Long id,
        Long userId,
        String login,
        String fullName,
        String action,
        String objectType,
        Long objectId,
        String details,
        LocalDateTime actionTime,
        String ip
) {
}
