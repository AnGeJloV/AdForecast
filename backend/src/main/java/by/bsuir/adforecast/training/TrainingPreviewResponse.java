package by.bsuir.adforecast.training;

import java.util.List;

public record TrainingPreviewResponse(
        int campaignCount,
        int recordCount,
        List<PlatformRow> byPlatform,
        String periodStart,
        String periodEnd,
        List<IncludedCampaign> included,
        List<ExcludedCampaign> excluded
) {

    public record PlatformRow(String platform, long campaigns, long records) {
    }

    public record IncludedCampaign(long id, String name, String platform, String budget,
                                   long days, String ratio) {
    }

    public record ExcludedCampaign(long id, String name, String platform, boolean inTraining, boolean isTest) {
    }
}
