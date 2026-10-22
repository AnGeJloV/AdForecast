package by.bsuir.adforecast.mlclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class MlServiceClient {

    private static final Logger log = LoggerFactory.getLogger(MlServiceClient.class);

    private final RestClient client;

    public MlServiceClient(@Value("${ml-service.base-url}") String baseUrl) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }

    public List<MlModelInfo> models() {
        try {
            MlModelsResponse response = client.get()
                    .uri("/models")
                    .retrieve()
                    .body(MlModelsResponse.class);
            return response == null ? List.of() : response.models();
        } catch (ResourceAccessException exception) {
            throw new MlServiceException("сервис прогнозирования недоступен", exception);
        } catch (RestClientResponseException exception) {
            log.error("ml-service /models вернул {}: {}", exception.getStatusCode(), exception.getResponseBodyAsString());
            throw new MlServiceException("ошибка сервиса прогнозирования", exception);
        }
    }

    public TrainAcceptedResponse train(List<Map<String, Object>> records, Integer ensembleSize, Double holdoutFraction,
                                       Map<String, Object> datasetSnapshot) {
        Map<String, Object> body = new HashMap<>();
        body.put("records", records);
        body.put("ensemble_size", ensembleSize);
        body.put("holdout_fraction", holdoutFraction);
        body.put("dataset_snapshot", datasetSnapshot);
        try {
            TrainAcceptedResponse response = client.post()
                    .uri("/train")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(TrainAcceptedResponse.class);
            if (response == null) {
                throw new MlServiceException("пустой ответ сервиса прогнозирования");
            }
            return response;
        } catch (ResourceAccessException exception) {
            throw new MlServiceException("сервис прогнозирования недоступен", exception);
        } catch (RestClientResponseException exception) {
            log.error("ml-service /train вернул {}: {}", exception.getStatusCode(), exception.getResponseBodyAsString());
            throw new MlServiceException("ошибка запуска обучения: " + exception.getResponseBodyAsString(), exception);
        }
    }

    public TrainStatusResponse trainStatus(String taskId) {
        try {
            TrainStatusResponse response = client.get()
                    .uri("/train/{taskId}", taskId)
                    .retrieve()
                    .body(TrainStatusResponse.class);
            if (response == null) {
                throw new MlServiceException("пустой ответ сервиса прогнозирования");
            }
            return response;
        } catch (ResourceAccessException exception) {
            throw new MlServiceException("сервис прогнозирования недоступен", exception);
        } catch (RestClientResponseException exception) {
            log.error("ml-service /train/{{taskId}} вернул {}: {}", exception.getStatusCode(), exception.getResponseBodyAsString());
            throw new MlServiceException("ошибка получения статуса обучения", exception);
        }
    }

    public MlPredictResponse predict(MlPredictCommand command) {
        Map<String, Object> body = Map.of(
                "platform", command.platform(),
                "budget", command.budget(),
                "duration_days", command.durationDays(),
                "start_month", command.startMonth(),
                "horizon_days", command.horizonDays(),
                "model_version", command.modelVersion()
        );
        try {
            MlPredictResponse response = client.post()
                    .uri("/predict")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(MlPredictResponse.class);
            if (response == null) {
                throw new MlServiceException("пустой ответ сервиса прогнозирования");
            }
            return response;
        } catch (ResourceAccessException exception) {
            throw new MlServiceException("сервис прогнозирования недоступен", exception);
        } catch (RestClientResponseException exception) {
            log.error("ml-service /predict вернул {}: {}", exception.getStatusCode(), exception.getResponseBodyAsString());
            throw new MlServiceException("ошибка сервиса прогнозирования", exception);
        }
    }
}
