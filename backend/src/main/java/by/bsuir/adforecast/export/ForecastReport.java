package by.bsuir.adforecast.export;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
        String verdict,
        String paybackRatio,
        String forecastRoi,
        String intervalLower,
        String intervalUpper,
        String agreement,
        String paybackMonths,
        String expectedProfit,
        String budgetRange,
        String durationDays,
        String history,
        String modelInfo,
        String mape
) {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public List<String[]> rows() {
        List<String[]> rows = new ArrayList<>(List.of(
                new String[]{"Идентификатор прогноза", String.valueOf(forecastId)},
                new String[]{"Дата расчета", startedAt == null ? "" : startedAt.format(FORMAT)},
                new String[]{"Кампания", campaignName + " (id " + campaignId + ")"},
                new String[]{"Рекламный кабинет", accountName},
                new String[]{"Платформа", platform},
                new String[]{"Бюджет кампании", budget},
                new String[]{"Статус кампании", campaignStatus},
                new String[]{"Длительность кампании, дней", durationDays},
                new String[]{"Вердикт", verdict},
                new String[]{"Прогнозный коэффициент окупаемости", paybackRatio},
                new String[]{"Прогнозный ROI, %", forecastRoi},
                new String[]{"Интервал прогноза (80 %), нижняя граница", intervalLower},
                new String[]{"Интервал прогноза (80 %), верхняя граница", intervalUpper},
                new String[]{"Согласованность моделей, %", agreement},
                new String[]{"Срок окупаемости, мес", paybackMonths},
                new String[]{"Ожидаемая прибыль, ден. ед.", expectedProfit},
                new String[]{"Окупаемый диапазон бюджета", budgetRange},
                new String[]{"Данные кампании на дату прогноза", history},
                new String[]{"Модель", modelInfo},
                new String[]{"MAPE модели, %", mape}
        ));
        return rows;
    }
}
