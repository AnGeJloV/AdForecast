package by.bsuir.adforecast.forecast;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.campaign.CampaignService;
import by.bsuir.adforecast.domain.AdAccount;
import by.bsuir.adforecast.domain.Campaign;
import by.bsuir.adforecast.domain.CampaignStatus;
import by.bsuir.adforecast.domain.Forecast;
import by.bsuir.adforecast.domain.ForecastResult;
import by.bsuir.adforecast.domain.ForecastStatus;
import by.bsuir.adforecast.domain.MlModel;
import by.bsuir.adforecast.domain.Organization;
import by.bsuir.adforecast.mlclient.MlPredictResponse;
import by.bsuir.adforecast.mlclient.MlServiceClient;
import by.bsuir.adforecast.repo.AdAccountRepository;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.ForecastParamRepository;
import by.bsuir.adforecast.repo.ForecastRepository;
import by.bsuir.adforecast.repo.ForecastResultRepository;
import by.bsuir.adforecast.repo.MlModelRepository;
import by.bsuir.adforecast.repo.UserRepository;
import by.bsuir.adforecast.training.OrgModelService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForecastServiceTest {

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private CampaignService campaignService;
    @Mock
    private AdAccountRepository adAccountRepository;
    @Mock
    private ForecastRepository forecastRepository;
    @Mock
    private ForecastParamRepository forecastParamRepository;
    @Mock
    private ForecastResultRepository forecastResultRepository;
    @Mock
    private MlModelRepository mlModelRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MlServiceClient mlServiceClient;
    @Mock
    private OrgModelService orgModelService;
    @Mock
    private AuditService auditService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private ForecastService service;

    private MlModel model(String name, String version, String mape) {
        MlModel model = new MlModel();
        model.setId(name.equals("adforecast_roi") ? 10L : 11L);
        model.setModelName(name);
        model.setModelVersion(version);
        model.setQualityMetric(new BigDecimal(mape));
        model.setIsActive(true);
        return model;
    }

    private Campaign campaign(String platform, CampaignStatus status) {
        Organization organization = new Organization();
        organization.setId(1L);
        organization.setName("Организация «Демо»");
        AdAccount account = new AdAccount();
        account.setId(4L);
        account.setPlatform(platform);
        account.setOrganization(organization);
        Campaign campaign = new Campaign();
        campaign.setId(1L);
        campaign.setAccount(account);
        campaign.setOrganization(organization);
        campaign.setCampaignName("VK — продвижение");
        campaign.setBudget(new BigDecimal("10000.00"));
        campaign.setCurrency("BYN");
        campaign.setStatus(status);
        return campaign;
    }

    private void stubModels(MlModel own) {
        when(mlModelRepository.findByOrganizationIdIsNullAndIsActiveTrue())
                .thenReturn(Optional.of(model("adforecast_roi", "v1", "8.06")));
        when(mlModelRepository.findByOrganizationIdAndIsActiveTrue(1L))
                .thenReturn(Optional.ofNullable(own));
    }

    private void stubOrganizations(int ownCampaigns) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(1L))).thenReturn(ownCampaigns);
    }

    private MlPredictResponse prediction(double ratio, double lower, double upper) {
        return new MlPredictResponse("adforecast_roi", "v1", ratio, (ratio - 1) * 100, lower, upper,
                0.85, 0.05, null, null, false, null, null, false, false, 100.0, 1000.0, false);
    }

    private Forecast savedForecast() {
        Forecast forecast = new Forecast();
        forecast.setStartedAt(LocalDateTime.of(2026, 10, 27, 12, 0));
        when(forecastRepository.save(any(Forecast.class))).thenAnswer(invocation -> {
            Forecast saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(100L);
            }
            return saved;
        });
        return forecast;
    }

    private Map<String, Object> snapshotRow() {
        return Map.of("days", 8, "cost", 1000.0, "revenue", 1700.0,
                "cost_last7", 800.0, "revenue_last7", 1300.0,
                "first_day", Date.valueOf("2026-10-01"));
    }

    @Test
    void runRejectsFinishedCampaign() {
        when(campaignRepository.findWithAccount(1L)).thenReturn(Optional.of(campaign("VK Реклама",
                CampaignStatus.FINISHED)));

        assertThatThrownBy(() -> service.run(1L, 5L, new ForecastRequest(60, 12, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("кампания завершена");

        verify(forecastRepository, never()).save(any(Forecast.class));
    }

    @Test
    void runRequiresAccess() {
        when(campaignRepository.findWithAccount(1L)).thenReturn(Optional.of(campaign("VK Реклама",
                CampaignStatus.PLANNED)));
        doThrow(new AccessDeniedException("нет доступа к кампании")).when(campaignService).requireAccess(5L, 1L);

        assertThatThrownBy(() -> service.run(1L, 5L, new ForecastRequest(60, 12, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void runPlannedCampaignWithoutDurationIsRejected() {
        when(campaignRepository.findWithAccount(1L)).thenReturn(Optional.of(campaign("VK Реклама",
                CampaignStatus.PLANNED)));
        when(jdbcTemplate.queryForList(anyString(), eq(1L), eq(1L), eq(1L))).thenReturn(List.of());
        savedForecast();

        assertThatThrownBy(() -> service.run(1L, 5L, new ForecastRequest(null, 12, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("укажите длительность");
    }

    @Test
    void plannedCampaignIsPredictedByGeneralModelOnly() {
        Campaign campaign = campaign("VK Реклама", CampaignStatus.PLANNED);
        when(campaignRepository.findWithAccount(1L)).thenReturn(Optional.of(campaign));
        when(jdbcTemplate.queryForList(anyString(), eq(1L), eq(1L), eq(1L))).thenReturn(List.of());
        stubModels(null);
        stubOrganizations(0);
        savedForecast();
        when(mlServiceClient.predict(any())).thenReturn(prediction(0.9, 0.7, 1.1));

        ForecastResponse response = service.run(1L, 5L, new ForecastRequest(60, 12, null));

        assertThat(response.basis())
                .isEqualTo("по общим данным рынка, у вашей организации пока нет завершённых кампаний");

        ArgumentCaptor<ForecastResult> results = ArgumentCaptor.forClass(ForecastResult.class);
        verify(forecastResultRepository).save(results.capture());
        ForecastResult result = results.getValue();
        assertThat(result.getVerdict()).isEqualTo("BORDERLINE");
        assertThat(result.getPaybackRatio()).isEqualByComparingTo("0.9000");
        assertThat(result.getIntervalLower()).isEqualByComparingTo("0.7000");
        assertThat(result.getIntervalUpper()).isEqualByComparingTo("1.1000");
        assertThat(result.getExpectedProfit()).isEqualByComparingTo("-1000.00");
        assertThat(result.getOwnWeight()).isEqualByComparingTo("0.000");
        assertThat(result.getDataNote()).isNull();
        ArgumentCaptor<Forecast> forecasts = ArgumentCaptor.forClass(Forecast.class);
        verify(forecastRepository, atLeastOnce()).save(forecasts.capture());
        assertThat(forecasts.getValue().getStatus()).isEqualTo(ForecastStatus.COMPLETED);
        assertThat(forecasts.getValue().getModel().getModelName()).isEqualTo("adforecast_roi");
        verify(auditService).record(eq(5L), eq("запуск прогноза"), eq("кампания"), eq(1L), anyString());
        verify(mlServiceClient, times(1)).predict(any());
    }

    @Test
    void activeCampaignBlendsOwnAndGeneralPredictions() {
        Campaign campaign = campaign("VK Реклама", CampaignStatus.ACTIVE);
        when(campaignRepository.findWithAccount(1L)).thenReturn(Optional.of(campaign));
        when(jdbcTemplate.queryForList(anyString(), eq(1L), eq(1L), eq(1L))).thenReturn(List.of(snapshotRow()));
        stubModels(model("adforecast_org_1", "v2", "8.47"));
        stubOrganizations(804);
        savedForecast();
        when(mlServiceClient.predict(any())).thenReturn(prediction(1.0, 0.9, 1.1),
                prediction(1.7, 1.5, 1.9));

        ForecastResponse response = service.run(1L, 5L, new ForecastRequest(38, null, "BLEND"));

        assertThat(response.basis()).contains("на 97 % по кампаниям вашей организации (804 завершено)");

        ArgumentCaptor<ForecastResult> results = ArgumentCaptor.forClass(ForecastResult.class);
        verify(forecastResultRepository).save(results.capture());
        ForecastResult result = results.getValue();
        assertThat(result.getVerdict()).isEqualTo("PROFITABLE");
        assertThat(result.getPaybackRatio()).isBetween(new BigDecimal("1.66"), new BigDecimal("1.70"));
        assertThat(result.getPaybackDays()).isEqualByComparingTo("8.0");
        assertThat(result.getOwnWeight()).isBetween(new BigDecimal("0.96"), new BigDecimal("0.98"));
        verify(auditService).record(eq(5L), eq("запуск прогноза"), eq("кампания"), eq(1L), anyString());
    }

    @Test
    void activeCampaignRejectsDurationShorterThanObservedData() {
        when(campaignRepository.findWithAccount(1L)).thenReturn(Optional.of(campaign("VK Реклама",
                CampaignStatus.ACTIVE)));
        when(jdbcTemplate.queryForList(anyString(), eq(1L), eq(1L), eq(1L))).thenReturn(List.of(snapshotRow()));
        savedForecast();

        assertThatThrownBy(() -> service.run(1L, 5L, new ForecastRequest(5, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже длится 8");

        verify(forecastResultRepository, never()).save(any());
        verify(auditService).record(eq(5L), eq("прогноз не рассчитан"), eq("кампания"), eq(1L), anyString());
    }

    @Test
    void ownScopeWithoutOwnModelUsesFactsBaseline() {
        Campaign campaign = campaign("VK Реклама", CampaignStatus.PLANNED);
        when(campaignRepository.findWithAccount(1L)).thenReturn(Optional.of(campaign));
        when(jdbcTemplate.queryForList(anyString(), eq(1L), eq(1L), eq(1L))).thenReturn(List.of());
        stubModels(null);
        stubOrganizations(2);
        savedForecast();
        when(jdbcTemplate.queryForList(anyString(), eq(1L))).thenReturn(List.of(
                Map.of("cost", 1000.0, "revenue", 1500.0, "days", 30L),
                Map.of("cost", 2000.0, "revenue", 1800.0, "days", 30L)));

        ForecastResponse response = service.run(1L, 5L, new ForecastRequest(60, 12, "OWN"));

        assertThat(response.basis()).contains("оценка по 2 вашим кампаниям");

        ArgumentCaptor<ForecastResult> results = ArgumentCaptor.forClass(ForecastResult.class);
        verify(forecastResultRepository).save(results.capture());
        ForecastResult result = results.getValue();
        assertThat(result.getVerdict()).isEqualTo("BORDERLINE");
        assertThat(result.getPaybackRatio()).isEqualByComparingTo("1.1000");
        assertThat(result.getAgreement()).isNull();
        assertThat(result.getModelScope()).isEqualTo("OWN");
        assertThat(result.getDataNote()).contains("данных очень мало");
        verify(mlServiceClient, never()).predict(any());
        verify(auditService).record(eq(5L), eq("запуск прогноза"), eq("кампания"), eq(1L), anyString());
    }

    @Test
    void ownScopeWithoutAnyDataIsRejected() {
        when(campaignRepository.findWithAccount(1L)).thenReturn(Optional.of(campaign("VK Реклама",
                CampaignStatus.PLANNED)));
        when(jdbcTemplate.queryForList(anyString(), eq(1L), eq(1L), eq(1L))).thenReturn(List.of());
        stubModels(null);
        stubOrganizations(0);
        savedForecast();
        when(jdbcTemplate.queryForList(anyString(), eq(1L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.run(1L, 5L, new ForecastRequest(60, 12, "OWN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("оценка только по своим данным невозможна");
    }

    @Test
    void budgetBelowPlatformMinimumReturnsWarningVerdict() {
        Campaign campaign = campaign("TikTok Ads", CampaignStatus.PLANNED);
        campaign.setBudget(new BigDecimal("3000.00"));
        when(campaignRepository.findWithAccount(1L)).thenReturn(Optional.of(campaign));
        when(jdbcTemplate.queryForList(anyString(), eq(1L), eq(1L), eq(1L))).thenReturn(List.of());
        stubModels(null);
        stubOrganizations(0);
        savedForecast();

        service.run(1L, 5L, new ForecastRequest(60, 12, null));

        ArgumentCaptor<ForecastResult> results = ArgumentCaptor.forClass(ForecastResult.class);
        verify(forecastResultRepository).save(results.capture());
        ForecastResult result = results.getValue();
        assertThat(result.getVerdict()).isEqualTo("BELOW_MINIMUM");
        assertThat(result.getPaybackRatio()).isNull();
        assertThat(result.getPaybackDays()).isNull();
        assertThat(result.getDataNote()).contains("TikTok");
        verify(mlServiceClient, never()).predict(any());
    }

    @Test
    void unknownChannelStillProducesEstimateWithWarning() {
        when(campaignRepository.findWithAccount(1L)).thenReturn(Optional.of(campaign("Авито",
                CampaignStatus.PLANNED)));
        when(jdbcTemplate.queryForList(anyString(), eq(1L), eq(1L), eq(1L))).thenReturn(List.of());
        stubModels(null);
        stubOrganizations(0);
        savedForecast();
        when(mlServiceClient.predict(any()))
                .thenReturn(unknownPlatform(prediction(1.1, 0.95, 1.25)));

        service.run(1L, 5L, new ForecastRequest(60, 12, null));

        ArgumentCaptor<ForecastResult> results = ArgumentCaptor.forClass(ForecastResult.class);
        verify(forecastResultRepository).save(results.capture());
        ForecastResult result = results.getValue();
        assertThat(result.getVerdict()).isEqualTo("BORDERLINE");
        assertThat(result.getPaybackRatio()).isEqualByComparingTo("1.1000");
        assertThat(result.getDataNote()).contains("грубая прикидка");
    }

    @Test
    void compareRejectsUnknownPlatform() {
        when(adAccountRepository.findAll()).thenReturn(List.of(accountOf("VK Реклама")));

        assertThatThrownBy(() -> service.compare(5L, new CompareRequest(List.of(
                new CompareRequest.CompareVariant("Авито", null, new BigDecimal("10000"), 60, 12)), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("неизвестная платформа");
    }

    @Test
    void compareRejectsUnsupportedCurrency() {
        when(adAccountRepository.findAll()).thenReturn(List.of(accountOf("VK Реклама")));
        when(userRepository.findById(5L)).thenReturn(Optional.of(userOf()));

        assertThatThrownBy(() -> service.compare(5L, new CompareRequest(List.of(
                new CompareRequest.CompareVariant("VK Реклама", "GBP", new BigDecimal("10000"), 60, 12)), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не поддерживается");
    }

    @Test
    void compareReturnsVerdictForEachVariant() {
        when(adAccountRepository.findAll()).thenReturn(List.of(accountOf("VK Реклама"), accountOf("Telegram Ads")));
        when(userRepository.findById(5L)).thenReturn(Optional.of(userOf()));
        stubModels(null);
        stubOrganizations(0);
        when(mlServiceClient.predict(any())).thenReturn(prediction(0.9, 0.7, 1.1),
                prediction(1.6, 1.4, 1.8));

        List<CompareResponse> responses = service.compare(5L, new CompareRequest(List.of(
                new CompareRequest.CompareVariant("VK Реклама", "BYN", new BigDecimal("10000"), 60, 12),
                new CompareRequest.CompareVariant("Telegram Ads", "USD", new BigDecimal("500"), 60, 12)), null));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).verdict()).isEqualTo("BORDERLINE");
        assertThat(responses.get(0).paybackRatio()).isEqualByComparingTo("0.9000");
        assertThat(responses.get(1).verdict()).isEqualTo("PROFITABLE");
        assertThat(responses.get(1).currency()).isEqualTo("USD");
        assertThat(responses.get(1).paybackMonths()).isNotNull();
    }

    @Test
    void historyRequiresAccessAndMapsResults() {
        Forecast forecast = new Forecast();
        forecast.setId(100L);
        forecast.setStatus(ForecastStatus.COMPLETED);
        forecast.setStartedAt(LocalDateTime.of(2026, 10, 26, 9, 0));
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(new Campaign()));
        when(forecastRepository.findByCampaignIdOrderByStartedAtDesc(1L)).thenReturn(List.of(forecast));
        when(forecastParamRepository.findByForecastId(100L)).thenReturn(Optional.empty());
        when(forecastResultRepository.findByForecastId(100L)).thenReturn(Optional.empty());

        assertThat(service.history(1L, 5L)).hasSize(1);

        doThrow(new AccessDeniedException("нет доступа к кампании")).when(campaignService).requireAccess(5L, 1L);
        assertThatThrownBy(() -> service.history(1L, 5L)).isInstanceOf(AccessDeniedException.class);
    }

    private AdAccount accountOf(String platform) {
        AdAccount account = new AdAccount();
        account.setPlatform(platform);
        return account;
    }

    private by.bsuir.adforecast.domain.User userOf() {
        Organization organization = new Organization();
        organization.setId(1L);
        by.bsuir.adforecast.domain.User user = new by.bsuir.adforecast.domain.User();
        user.setId(5L);
        user.setOrganization(organization);
        return user;
    }

    private MlPredictResponse unknownPlatform(MlPredictResponse source) {
        return new MlPredictResponse(source.modelName(), source.modelVersion(), source.paybackRatio(),
                source.forecastRoi(), source.intervalLower(), source.intervalUpper(), source.agreement(),
                source.memberSpread(), source.paybackDays(), source.paybackMonths(), source.paybackWithinDuration(),
                source.profitableBudgetMin(), source.profitableBudgetMax(), source.profitableAtAnyBudget(),
                source.budgetOutOfRange(), source.dayBudgetMin(), source.dayBudgetMax(), true);
    }
}
