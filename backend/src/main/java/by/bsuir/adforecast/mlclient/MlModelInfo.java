package by.bsuir.adforecast.mlclient;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record MlModelInfo(
        @JsonProperty("version") String version,
        @JsonProperty("trained_at") String trainedAt,
        @JsonProperty("mape") double mape,
        @JsonProperty("r2") double r2,
        @JsonProperty("mae") double mae,
        @JsonProperty("holdout_size") int holdoutSize,
        @JsonProperty("ensemble_size") int ensembleSize,
        @JsonProperty("features") List<String> features,
        @JsonProperty("dataset_snapshot") Map<String, Object> datasetSnapshot
) {
}
