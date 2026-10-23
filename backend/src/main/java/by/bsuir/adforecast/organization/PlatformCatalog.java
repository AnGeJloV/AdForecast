package by.bsuir.adforecast.organization;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Каталог рекламных площадок с экономикой из открытых источников (октябрь 2026).
 * Служит единственным источником правды: набор кабинетов новой организации, допустимые
 * названия площадок и минимальный осмысленный дневной бюджет для проверки прогноза.
 *
 * Курсы для пересчёта в BYN: НБРБ на 22.10.2026 — 1 USD = 2,9713, 1 EUR = 3,3337,
 * 100 RUB = 3,6254.
 *
 * Источники: click.ru «Пульс» (CPC/CPM по площадкам, Q1–Q2 2026), официальная справка
 * Яндекс Директа (минимумы бюджета), справка TikTok Ads (минимальный дневной бюджет $50),
 * ECOM Digital (бюджеты контекстной рекламы в Беларуси от 300 BYN в месяц на систему),
 * MetricNexus и Triple Whale (CPC/CTR/ROAS Google, Meta, TikTok 2026),
 * Kufar Business и belkaads (баннер от 12 BYN за 1000 показов).
 */
public final class PlatformCatalog {

    public record PlatformInfo(String name, BigDecimal cpc, BigDecimal minDayBudget, String note) {
    }

    private static final Map<String, PlatformInfo> PLATFORMS = new LinkedHashMap<>();

    private static void register(String name, String cpc, String minDayBudget, String note) {
        PLATFORMS.put(name, new PlatformInfo(name, new BigDecimal(cpc), new BigDecimal(minDayBudget), note));
    }

    static {
        register("Яндекс Директ", "1.46", "10",
                "CPC поиска 40,3 ₽ (click.ru, Q2 2026); от 300 BYN/мес на систему (ECOM Digital, 2026)");
        register("Яндекс Директ — сети", "0.31", "10",
                "CPC в сетях 8,56 ₽, CPM 102,6 ₽ (click.ru, Q2 2026)");
        register("Google Ads", "1.05", "10",
                "CPC от 0,3 $ в спокойных нишах (topsite.by, 2025), CTR 6,3 % (MetricNexus, 2026)");
        register("VK Реклама", "0.33", "10",
                "CPC 9,15 ₽, CTR 0,97 % (click.ru, 2026)");
        register("myTarget", "0.73", "10",
                "CPC 20 ₽ (click.ru); кабинеты постепенно переходят в VK Ads");
        register("Telegram Ads", "0.33", "10",
                "CPM от 0,5–1 € за 1000 показов; тест от 50 000 ₽/мес (агентства, 2026)");
        register("TikTok Ads", "1.84", "148",
                "официальный минимум 50 $ в день, CPC 0,62 $ (справка TikTok, MetricNexus, 2026)");
        register("Kufar", "0.80", "10",
                "баннер от 12 BYN за 1000 показов (belkaads, 2026), 5+ млн покупателей (Kufar Business)");
        register("Email-платформа", "0.02", "0",
                "собственная база, платформа рассылок: минимального бюджета нет");
    }

    private PlatformCatalog() {
    }

    public static List<String> names() {
        return List.copyOf(PLATFORMS.keySet());
    }

    public static List<PlatformInfo> all() {
        return List.copyOf(PLATFORMS.values());
    }

    public static Optional<PlatformInfo> find(String platform) {
        return Optional.ofNullable(PLATFORMS.get(platform));
    }

    public static boolean isKnown(String platform) {
        return PLATFORMS.containsKey(platform);
    }
}
