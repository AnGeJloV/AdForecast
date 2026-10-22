package by.bsuir.adforecast.forecast;

import by.bsuir.adforecast.campaign.CampaignService;
import by.bsuir.adforecast.domain.AdAccount;
import by.bsuir.adforecast.domain.Campaign;
import by.bsuir.adforecast.domain.Forecast;
import by.bsuir.adforecast.domain.ForecastParam;
import by.bsuir.adforecast.domain.ForecastResult;
import by.bsuir.adforecast.domain.ForecastStatus;
import by.bsuir.adforecast.domain.MlModel;
import by.bsuir.adforecast.mlclient.MlModelInfo;
import by.bsuir.adforecast.mlclient.MlPredictCommand;
import by.bsuir.adforecast.mlclient.MlPredictResponse;
import by.bsuir.adforecast.mlclient.MlServiceClient;
import by.bsuir.adforecast.mlclient.MlServiceException;
import by.bsuir.adforecast.repo.AdAccountRepository;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.ForecastParamRepository;
import by.bsuir.adforecast.repo.ForecastRepository;
import by.bsuir.adforecast.repo.ForecastResultRepository;
import by.bsuir.adforecast.repo.MlModelRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ForecastService {

    private static final String MODEL_NAME = "adforecast_roi";
    private static final double DAYS_PER_MONTH = 30.44;

    private static final String LIST_ALL_SQL = """
            select f.forecast_id, f.campaign_id, c.campaign_name, f.started_at, f.status,
                   m.model_version, p.horizon_days, r.forecast_roi, r.payback_months,
                   r.payback_probability, r.mape
            from forecasts f
            join campaigns c on c.campaign_id = f.campaign_id
            join ml_models m on m.model_id = f.model_id
            left join forecast_params p on p.forecast_id = f.forecast_id
            left join forecast_results r on r.forecast_id = f.forecast_id
            where f.campaign_id in (select campaign_id from campaign_access where user_id = ?)
            order by f.started_at desc
            limit 200
            """;

    private final CampaignRepository campaignRepository;
    private final CampaignService campaignService;
    private final AdAccountRepository adAccountRepository;
    private final ForecastRepository forecastRepository;
    private final ForecastParamRepository forecastParamRepository;
    private final ForecastResultRepository forecastResultRepository;
    private final MlModelRepository mlModelRepository;
    private final MlServiceClient mlServiceClient;
    private final JdbcTemplate jdbcTemplate;

    public ForecastService(CampaignRepository campaignRepository,
                           CampaignService campaignService,
                           AdAccountRepository adAccountRepository,
                           ForecastRepository forecastRepository,
                           ForecastParamRepository forecastParamRepository,
                           ForecastResultRepository forecastResultRepository,
                           MlModelRepository mlModelRepository,
                           MlServiceClient mlServiceClient,
                           JdbcTemplate jdbcTemplate) {
        this.campaignRepository = campaignRepository;
        this.campaignService = campaignService;
        this.adAccountRepository = adAccountRepository;
        this.forecastRepository = forecastRepository;
        this.forecastParamRepository = forecastParamRepository;
        this.forecastResultRepository = forecastResultRepository;
        this.mlModelRepository = mlModelRepository;
        this.mlServiceClient = mlServiceClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    public ForecastResponse run(long campaignId, long userId, ForecastRequest request) {
        Campaign campaign = campaignRepository.findWithAccount(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        campaignService.requireAccess(userId, campaignId);
        MlModel model = activeModel();

        Forecast forecast = new Forecast();
        forecast.setCampaign(campaign);
        forecast.setModel(model);
        forecast.setStartedAt(LocalDateTime.now());
        forecast.setStatus(ForecastStatus.PENDING);
        forecast = forecastRepository.save(forecast);

        forecast.setStatus(ForecastStatus.RUNNING);
        forecast = forecastRepository.save(forecast);

        int startMonth = request.startMonth() == null ? LocalDate.now().getMonthValue() : request.startMonth();
        try {
            MlPredictResponse prediction = mlServiceClient.predict(new MlPredictCommand(
                    campaign.getAccount().getPlatform(),
                    campaign.getBudget().doubleValue(),
                    request.horizonDays(),
                    startMonth,
                    request.horizonDays(),
                    model.getModelVersion()));

            ForecastParam param = new ForecastParam();
            param.setForecast(forecast);
            param.setHorizonDays(request.horizonDays());
            param.setConfidenceLevel(request.confidenceLevel());
            param.setUseSeasonality(request.useSeasonality());
            forecastParamRepository.save(param);

            ForecastResult result = new ForecastResult();
            result.setForecast(forecast);
            result.setForecastRoi(BigDecimal.valueOf(prediction.forecastRoi()));
            result.setPaybackMonths(prediction.paybackMonths() == null
                    ? null
                    : BigDecimal.valueOf(prediction.paybackMonths()));
            result.setPaybackProbability(BigDecimal.valueOf(prediction.paybackProbability()));
            result.setMape(model.getQualityMetric());
            forecastResultRepository.save(result);

            forecast.setStatus(ForecastStatus.COMPLETED);
            forecastRepository.save(forecast);
            return new ForecastResponse(
                    forecast.getId(),
                    forecast.getStatus().name(),
                    forecast.getStartedAt(),
                    model.getModelVersion(),
                    request.horizonDays(),
                    request.confidenceLevel(),
                    request.useSeasonality(),
                    BigDecimal.valueOf(prediction.forecastRoi()),
                    prediction.paybackMonths() == null ? null : BigDecimal.valueOf(prediction.paybackMonths()),
                    prediction.paybackDays(),
                    prediction.paybackWithinHorizon(),
                    BigDecimal.valueOf(prediction.paybackProbability()),
                    model.getQualityMetric()
            );
        } catch (MlServiceException exception) {
            forecast.setStatus(ForecastStatus.FAILED);
            forecastRepository.save(forecast);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<ForecastResponse> history(long campaignId, long userId) {
        campaignRepository.findById(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        campaignService.requireAccess(userId, campaignId);
        return historyOf(campaignId);
    }

    /** История прогнозов для админского просмотра: без проверки владения, доступ даёт контроллер. */
    @Transactional(readOnly = true)
    public List<ForecastResponse> historyForInspection(long campaignId) {
        campaignRepository.findById(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        return historyOf(campaignId);
    }

    private List<ForecastResponse> historyOf(long campaignId) {
        return forecastRepository.findByCampaignIdOrderByStartedAtDesc(campaignId).stream()
                .map(forecast -> {
                    ForecastParam param = forecastParamRepository.findByForecastId(forecast.getId()).orElse(null);
                    ForecastResult result = forecastResultRepository.findByForecastId(forecast.getId()).orElse(null);
                    return toHistoryItem(forecast, param, result);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ForecastListItem> listAll(long userId) {
        return jdbcTemplate.query(LIST_ALL_SQL, (rs, rowNum) -> new ForecastListItem(
                rs.getLong("forecast_id"),
                rs.getLong("campaign_id"),
                rs.getString("campaign_name"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getString("status"),
                rs.getString("model_version"),
                rs.getObject("horizon_days", Integer.class),
                rs.getBigDecimal("forecast_roi"),
                rs.getBigDecimal("payback_months"),
                rs.getBigDecimal("payback_probability"),
                rs.getBigDecimal("mape")
        ), userId);
    }

    public List<CompareResponse> compare(CompareRequest request) {
        MlModel model = activeModel();
        Set<String> platforms = adAccountRepository.findAll().stream()
                .map(AdAccount::getPlatform)
                .collect(Collectors.toSet());
        for (CompareRequest.CompareVariant variant : request.variants()) {
            if (!platforms.contains(variant.platform())) {
                throw new IllegalArgumentException("неизвестная платформа: " + variant.platform());
            }
        }
        List<CompareResponse> responses = new ArrayList<>();
        for (CompareRequest.CompareVariant variant : request.variants()) {
            MlPredictResponse prediction = mlServiceClient.predict(new MlPredictCommand(
                    variant.platform(),
                    variant.budget().doubleValue(),
                    variant.durationDays(),
                    variant.startMonth(),
                    variant.durationDays(),
                    model.getModelVersion()));
            responses.add(new CompareResponse(
                    variant.platform(),
                    variant.budget(),
                    variant.durationDays(),
                    variant.startMonth(),
                    BigDecimal.valueOf(prediction.forecastRoi()),
                    prediction.paybackMonths() == null ? null : BigDecimal.valueOf(prediction.paybackMonths()),
                    BigDecimal.valueOf(prediction.paybackProbability()),
                    prediction.paybackWithinHorizon()
            ));
        }
        return responses;
    }

    private MlModel activeModel() {
        Optional<MlModel> active = mlModelRepository.findByIsActiveTrue();
        if (active.isPresent()) {
            return active.get();
        }
        List<MlModelInfo> models = mlServiceClient.models();
        if (models.isEmpty()) {
            throw new MlServiceException("нет обученных моделей прогнозирования");
        }
        MlModelInfo latest = models.get(models.size() - 1);
        MlModel model = new MlModel();
        model.setModelName(MODEL_NAME);
        model.setModelVersion(latest.version());
        model.setTrainedAt(OffsetDateTime.parse(latest.trainedAt()).toLocalDateTime());
        model.setQualityMetric(BigDecimal.valueOf(latest.mape()));
        model.setIsActive(true);
        return mlModelRepository.save(model);
    }

    private ForecastResponse toHistoryItem(Forecast forecast, ForecastParam param, ForecastResult result) {
        return new ForecastResponse(
                forecast.getId(),
                forecast.getStatus().name(),
                forecast.getStartedAt(),
                forecast.getModel().getModelVersion(),
                param == null ? null : param.getHorizonDays(),
                param == null ? null : param.getConfidenceLevel(),
                param == null ? null : param.getUseSeasonality(),
                result == null ? null : result.getForecastRoi(),
                result == null ? null : result.getPaybackMonths(),
                null,
                result != null && result.getPaybackMonths() != null && param != null
                        && result.getPaybackMonths().doubleValue() * DAYS_PER_MONTH <= param.getHorizonDays(),
                result == null ? null : result.getPaybackProbability(),
                result == null ? null : result.getMape()
        );
    }
}
