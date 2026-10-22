package by.bsuir.adforecast.admin;

import java.time.LocalDateTime;
import java.util.List;

public record AdminModelResponse(
        long modelId,
        String version,
        LocalDateTime trainedAt,
        double mape,
        boolean isActive,
        boolean available,
        DatasetSnapshotResponse snapshot
) {

    public record DatasetSnapshotResponse(
            List<Long> campaignIds,
            String rule,
            String hash
    ) {
    }
}
