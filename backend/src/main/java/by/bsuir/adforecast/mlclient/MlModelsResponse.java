package by.bsuir.adforecast.mlclient;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record MlModelsResponse(@JsonProperty("models") List<MlModelInfo> models) {
}
