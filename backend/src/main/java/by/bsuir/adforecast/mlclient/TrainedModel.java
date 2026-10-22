package by.bsuir.adforecast.mlclient;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record TrainedModel(
        @JsonProperty("version") String version,
        @JsonProperty("trained_at") String trainedAt,
        @JsonProperty("mape") double mape,
        @JsonProperty("r2") double r2,
        @JsonProperty("mae") double mae,
        @JsonProperty("holdout_size") int holdoutSize,
        @JsonProperty("ensemble_size") int ensembleSize,
        @JsonProperty("dataset_snapshot") Map<String, Object> datasetSnapshot
) {

    public List<Long> campaignIds() {
        Object ids = datasetSnapshot == null ? null : datasetSnapshot.get("campaign_ids");
        return ids == null ? List.of() : ((List<?>) ids).stream().map(id -> ((Number) id).longValue()).toList();
    }

    public String selectionRule() {
        Object rule = datasetSnapshot == null ? null : datasetSnapshot.get("rule");
        return rule == null ? "" : rule.toString();
    }
}
