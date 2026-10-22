package by.bsuir.adforecast.upload;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record UploadListItem(
        Long id,
        Long campaignId,
        String campaignName,
        String fileName,
        String fileFormat,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDateTime uploadedAt,
        long rowsCount
) {
}
