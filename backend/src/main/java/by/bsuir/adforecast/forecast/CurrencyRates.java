package by.bsuir.adforecast.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Пересчёт денег в базовую валюту модели (BYN) и обратно. Курсы — официальные курсы
 * Национального банка Республики Беларусь на 22.10.2026: 1 USD = 2,9713 BYN,
 * 1 EUR = 3,3337 BYN, 100 RUB = 3,6254 BYN.
 *
 * Модель обучена на бюджетах в BYN, поэтому перед прогнозом бюджет кампании приводится
 * к BYN, а денежные ответы модели (окно бюджета, границы дневного бюджета) — обратно
 * в валюту кампании. В реальной эксплуатации курсы берутся из API НБРБ, здесь —
 * зафиксированные значения с датой, чтобы прогнозы были воспроизводимы.
 */
public final class CurrencyRates {

    public static final String BASE = "BYN";

    private static final Map<String, BigDecimal> PER_UNIT = new LinkedHashMap<>();
    private static final List<String> SUPPORTED = List.of("BYN", "USD", "EUR", "RUB");

    static {
        PER_UNIT.put("BYN", BigDecimal.ONE);
        PER_UNIT.put("USD", new BigDecimal("2.9713"));
        PER_UNIT.put("EUR", new BigDecimal("3.3337"));
        PER_UNIT.put("RUB", new BigDecimal("0.036254"));
    }

    private CurrencyRates() {
    }

    public static List<String> supported() {
        return SUPPORTED;
    }

    public static boolean isSupported(String currency) {
        return currency != null && PER_UNIT.containsKey(currency.toUpperCase());
    }

    public static BigDecimal toBase(String currency, BigDecimal amount) {
        return amount.multiply(rate(currency)).setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal fromBase(String currency, BigDecimal amount) {
        return amount.divide(rate(currency), 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal fromBase(String currency, double amount) {
        return fromBase(currency, BigDecimal.valueOf(amount));
    }

    private static BigDecimal rate(String currency) {
        BigDecimal rate = currency == null ? null : PER_UNIT.get(currency.toUpperCase());
        if (rate == null) {
            throw new IllegalArgumentException(
                    "валюта «" + currency + "» не поддерживается — доступны " + String.join(", ", SUPPORTED));
        }
        return rate;
    }
}
