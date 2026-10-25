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
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(75_000);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
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

    public void deleteModel(String modelName, String version) {
        try {
            client.delete()
                    .uri("/models/{name}/{version}", modelName, version)
                    .retrieve()
                    .toBodilessEntity();
        } catch (ResourceAccessException exception) {
            throw new MlServiceException("сервис прогнозирования недоступен", exception);
        } catch (RestClientResponseException exception) {
            log.error("ml-service DELETE вернул {}: {}", exception.getStatusCode(), exception.getResponseBodyAsString());
            throw new MlServiceException("ошибка удаления модели: " + exception.getResponseBodyAsString(), exception);
        }
    }

    public TrainAcceptedResponse train(List<Map<String, Object>> records, Integer ensembleSize, Double holdoutFraction,
                                       String modelName, Map<String, Object> datasetSnapshot) {
        Map<String, Object> body = new HashMap<>();
        body.put("records", records);
        body.put("ensemble_size", ensembleSize);
        body.put("holdout_fraction", holdoutFraction);
        body.put("model_name", modelName);
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
        Map<String, Object> body = new HashMap<>();
        body.put("platform", command.platform());
        body.put("budget", command.budget());
        body.put("duration_days", command.durationDays());
        body.put("start_month", command.startMonth());
        if (command.observed() != null) {
            Map<String, Object> observed = new HashMap<>();
            observed.put("days", command.observed().days());
            observed.put("cost_to_date", command.observed().costToDate());
            observed.put("revenue_to_date", command.observed().revenueToDate());
            observed.put("cost_last7", command.observed().costLast7());
            observed.put("revenue_last7", command.observed().revenueLast7());
            body.put("observed", observed);
        }
        body.put("model_name", command.modelName());
        body.put("model_version", command.modelVersion());
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
