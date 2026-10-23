package by.bsuir.adforecast.mlclient;

public record MlPredictCommand(String platform, double budget, int durationDays, int startMonth,
                               MlObservedState observed, String modelName, String modelVersion) {
}
