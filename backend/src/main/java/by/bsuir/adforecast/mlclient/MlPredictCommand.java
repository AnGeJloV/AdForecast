package by.bsuir.adforecast.mlclient;

public record MlPredictCommand(String platform, double budget, int durationDays, int startMonth,
                               int horizonDays, String modelVersion) {
}
