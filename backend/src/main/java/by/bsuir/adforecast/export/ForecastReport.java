package by.bsuir.adforecast.export;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public record ForecastReport(
        Long forecastId,
        LocalDateTime startedAt,
        String campaignName,
        Long campaignId,
        String accountName,
        String platform,
        String budget,
        String campaignStatus,
        Integer horizonDays,
        String confidenceLevel,
        String useSeasonality,
        String forecastRoi,
        String paybackMonths,
        String paybackProbability,
        String modelVersion,
        String mape
) {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public List<String[]> rows() {
        return List.of(
                new String[]{"Идентификатор прогноза", String.valueOf(forecastId)},
                new String[]{"Дата расчета", startedAt == null ? "" : startedAt.format(FORMAT)},
                new String[]{"Кампания", campaignName + " (id " + campaignId + ")"},
                new String[]{"Рекламный кабинет", accountName},
                new String[]{"Платформа", platform},
                new String[]{"Бюджет кампании", budget},
                new String[]{"Статус кампании", campaignStatus},
                new String[]{"Горизонт планирования, дней", String.valueOf(horizonDays)},
                new String[]{"Доверительный уровень", confidenceLevel},
                new String[]{"Учет сезонности", useSeasonality},
                new String[]{"Прогнозный ROI, %", forecastRoi},
                new String[]{"Срок окупаемости, мес", paybackMonths},
                new String[]{"Вероятность окупаемости", paybackProbability},
                new String[]{"Версия модели", modelVersion},
                new String[]{"MAPE модели, %", mape}
        );
    }
}
