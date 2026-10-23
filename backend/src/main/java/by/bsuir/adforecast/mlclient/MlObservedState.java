package by.bsuir.adforecast.mlclient;

public record MlObservedState(int days, double costToDate, double revenueToDate,
                               double costLast7, double revenueLast7) {
}
