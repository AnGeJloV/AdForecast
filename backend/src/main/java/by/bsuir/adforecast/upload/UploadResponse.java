package by.bsuir.adforecast.upload;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record UploadResponse(
        Long id,
        String fileName,
        String fileFormat,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDateTime uploadedAt,
        long rowsCount
) {

    public static UploadResponse of(by.bsuir.adforecast.domain.Upload upload, long rowsCount) {
        return new UploadResponse(
                upload.getId(),
                upload.getFileName(),
                upload.getFileFormat().name(),
                upload.getPeriodStart(),
                upload.getPeriodEnd(),
                upload.getUploadedAt(),
                rowsCount
        );
    }
}
