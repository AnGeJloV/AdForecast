package by.bsuir.adforecast.mlclient;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TrainStatusResponse(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("status") String status,
        @JsonProperty("model") TrainedModel model,
        @JsonProperty("error") String error
) {
}
