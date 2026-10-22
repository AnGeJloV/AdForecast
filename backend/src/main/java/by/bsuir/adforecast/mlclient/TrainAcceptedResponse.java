package by.bsuir.adforecast.mlclient;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TrainAcceptedResponse(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("status") String status
) {
}
