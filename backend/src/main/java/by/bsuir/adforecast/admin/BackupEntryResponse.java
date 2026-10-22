package by.bsuir.adforecast.admin;

import java.time.LocalDateTime;

public record BackupEntryResponse(String name, long size, LocalDateTime lastModified) {
}
