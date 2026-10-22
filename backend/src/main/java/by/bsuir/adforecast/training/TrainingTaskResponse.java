package by.bsuir.adforecast.training;

import java.util.List;

public record TrainingTaskResponse(
        String taskId,
        String status,
        TrainedModelInfo model,
        String error
) {

    public record TrainedModelInfo(
            String version,
            String trainedAt,
            double mape,
            double r2,
            double mae,
            int holdoutSize,
            int ensembleSize,
            int campaignCount,
            List<Long> campaignIds,
            String selectionRule
    ) {
    }
}
