package by.bsuir.adforecast.campaign;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.domain.AdAccount;
import by.bsuir.adforecast.domain.Campaign;
import by.bsuir.adforecast.domain.CampaignAccess;
import by.bsuir.adforecast.domain.CampaignStatus;
import by.bsuir.adforecast.domain.Organization;
import by.bsuir.adforecast.domain.User;
import by.bsuir.adforecast.repo.AdAccountRepository;
import by.bsuir.adforecast.repo.CampaignAccessRepository;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private CampaignAccessRepository campaignAccessRepository;
    @Mock
    private AdAccountRepository adAccountRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private CampaignService service;

    private Organization organization(long id) {
        Organization organization = new Organization();
        organization.setId(id);
        organization.setName("Организация «" + id + "»");
        return organization;
    }

    private AdAccount account(long id, Organization organization) {
        AdAccount account = new AdAccount();
        account.setId(id);
        account.setAccountName("Яндекс Директ_кабинет");
        account.setPlatform("Яндекс Директ");
        account.setOrganization(organization);
        return account;
    }

    private User user(long id, Organization organization) {
        User user = new User();
        user.setId(id);
        user.setOrganization(organization);
        return user;
    }

    private Campaign campaign(long id, AdAccount account) {
        Campaign campaign = new Campaign();
        campaign.setId(id);
        campaign.setAccount(account);
        campaign.setOrganization(account.getOrganization());
        campaign.setCampaignName("Кампания");
        campaign.setBudget(new BigDecimal("1000.00"));
        campaign.setCurrency("BYN");
        campaign.setStatus(CampaignStatus.ACTIVE);
        return campaign;
    }

    private CampaignSummaryProjection summary() {
        return summary(1L);
    }

    private CampaignSummaryProjection summary(long campaignId) {
        CampaignSummaryProjection summary = mock(CampaignSummaryProjection.class);
        when(summary.getCampaignId()).thenReturn(campaignId);
        when(summary.getCampaignName()).thenReturn("Кампания");
        when(summary.getStatus()).thenReturn("ACTIVE");
        when(summary.getBudget()).thenReturn(new BigDecimal("1000.00"));
        when(summary.getCurrency()).thenReturn("BYN");
        when(summary.getPlatform()).thenReturn("Яндекс Директ");
        when(summary.getUploadCount()).thenReturn(2L);
        when(summary.getDaysCount()).thenReturn(30L);
        when(summary.getTotalCost()).thenReturn(new BigDecimal("800.00"));
        when(summary.getTotalRevenue()).thenReturn(new BigDecimal("992.00"));
        when(summary.getActualRoi()).thenReturn(new BigDecimal("24.00"));
        when(summary.getTotalImpressions()).thenReturn(1000L);
        when(summary.getTotalClicks()).thenReturn(50L);
        return summary;
    }

    @Test
    void getWithoutAccessIsDenied() {
        when(campaignAccessRepository.existsByUserIdAndCampaignId(5L, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.get(1L, 5L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getReturnsDetailWithoutForecastStatistics() {
        AdAccount account = account(4L, organization(1L));
        when(campaignAccessRepository.existsByUserIdAndCampaignId(5L, 1L)).thenReturn(true);
        CampaignSummaryProjection summary = summary();
        when(campaignRepository.findWithAccount(1L)).thenReturn(Optional.of(campaign(1L, account)));
        when(campaignRepository.findSummary(1L)).thenReturn(Optional.of(summary));
        when(campaignRepository.findForecastStats(1L)).thenReturn(Optional.empty());

        CampaignDetailResponse detail = service.get(1L, 5L);

        assertThat(detail.id()).isEqualTo(1L);
        assertThat(detail.accountId()).isEqualTo(4L);
        assertThat(detail.accountName()).isEqualTo("Яндекс Директ_кабинет");
        assertThat(detail.platform()).isEqualTo("Яндекс Директ");
        assertThat(detail.uploadCount()).isEqualTo(2L);
        assertThat(detail.actualRoi()).isEqualByComparingTo("24.00");
        assertThat(detail.forecastCount()).isZero();
        assertThat(detail.latestVerdict()).isNull();
    }

    @Test
    void createRejectsAccountOfAnotherOrganization() {
        when(userRepository.findById(5L)).thenReturn(Optional.of(user(5L, organization(1L))));
        when(adAccountRepository.findById(4L)).thenReturn(Optional.of(account(4L, organization(2L))));

        assertThatThrownBy(() -> service.create(5L,
                new CampaignCreateRequest(4L, "Кампания", new BigDecimal("1000"), "BYN")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("нет доступа к рекламному кабинету");

        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    @Test
    void createRejectsUnsupportedCurrency() {
        when(userRepository.findById(5L)).thenReturn(Optional.of(user(5L, organization(1L))));
        when(adAccountRepository.findById(4L)).thenReturn(Optional.of(account(4L, organization(1L))));

        assertThatThrownBy(() -> service.create(5L,
                new CampaignCreateRequest(4L, "Кампания", new BigDecimal("1000"), "GBP")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не поддерживается");
    }

    @Test
    void createSavesPlannedCampaignAndGrantsAccess() {
        AdAccount account = account(4L, organization(1L));
        when(userRepository.findById(5L)).thenReturn(Optional.of(user(5L, organization(1L))));
        when(adAccountRepository.findById(4L)).thenReturn(Optional.of(account));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(invocation -> {
            Campaign saved = invocation.getArgument(0);
            saved.setId(20L);
            return saved;
        });
        CampaignSummaryProjection summary = summary(20L);
        when(campaignRepository.findSummary(20L)).thenReturn(Optional.of(summary));

        CampaignSummaryResponse response = service.create(5L,
                new CampaignCreateRequest(4L, "Кампания", new BigDecimal("1000"), "BYN"));

        assertThat(response.id()).isEqualTo(20L);
        verify(campaignRepository).save(any(Campaign.class));
        verify(campaignAccessRepository).save(any(CampaignAccess.class));
        verify(auditService).record(eq(5L), eq("создание кампании"), eq("кампания"), eq(20L),
                contains("планируется"));
    }

    @Test
    void updateRecordsChangeAndCompletionAuditsOnFinish() {
        AdAccount account = account(4L, organization(1L));
        Campaign campaign = campaign(1L, account);
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign));
        when(campaignAccessRepository.existsByUserIdAndCampaignId(5L, 1L)).thenReturn(true);
        CampaignSummaryProjection summary = summary();
        when(campaignRepository.findSummary(1L)).thenReturn(Optional.of(summary));

        service.update(1L, 5L, new CampaignUpdateRequest("Кампания", new BigDecimal("1000.00"), "BYN",
                CampaignStatus.FINISHED));

        verify(auditService, times(1)).record(eq(5L), eq("изменение кампании"), eq("кампания"), eq(1L),
                contains("завершена"));
        verify(auditService, times(1)).record(eq(5L), eq("завершение кампании"), eq("кампания"), eq(1L),
                contains("окупаемость 1.24"));
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.FINISHED);
    }

    @Test
    void updateWritesNoAuditWithoutChanges() {
        AdAccount account = account(4L, organization(1L));
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign(1L, account)));
        when(campaignAccessRepository.existsByUserIdAndCampaignId(5L, 1L)).thenReturn(true);
        CampaignSummaryProjection summary = summary();
        when(campaignRepository.findSummary(1L)).thenReturn(Optional.of(summary));

        service.update(1L, 5L, new CampaignUpdateRequest("Кампания", new BigDecimal("1000.00"), "BYN",
                CampaignStatus.ACTIVE));

        verifyNoInteractions(auditService);
    }

    @Test
    void updateRejectsUnsupportedCurrency() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign(1L, account(4L, organization(1L)))));
        when(campaignAccessRepository.existsByUserIdAndCampaignId(5L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(1L, 5L,
                new CampaignUpdateRequest("Кампания", new BigDecimal("1000.00"), "GBP", CampaignStatus.ACTIVE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metricsRequireExistingCampaignAndAccess() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.metrics(1L, 5L)).isInstanceOf(EntityNotFoundException.class);

        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign(1L, account(4L, organization(1L)))));
        when(campaignAccessRepository.existsByUserIdAndCampaignId(5L, 1L)).thenReturn(false);
        assertThatThrownBy(() -> service.metrics(1L, 5L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void metricsReturnsDailyPointsFromDatabase() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign(1L, account(4L, organization(1L)))));
        when(campaignAccessRepository.existsByUserIdAndCampaignId(5L, 1L)).thenReturn(true);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1L))).thenReturn(List.of(
                new DailyMetricPoint(LocalDate.of(2026, 10, 1), new BigDecimal("50"), new BigDecimal("80"), 900, 30)));

        List<DailyMetricPoint> metrics = service.metrics(1L, 5L);

        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(0).cost()).isEqualByComparingTo("50");
        assertThat(metrics.get(0).clicks()).isEqualTo(30);
    }

    @Test
    void listForUserMapsSummaries() {
        CampaignSummaryProjection summary = summary();
        when(campaignRepository.findUserSummaries(5L)).thenReturn(List.of(summary));

        List<CampaignSummaryResponse> campaigns = service.listForUser(5L);

        assertThat(campaigns).hasSize(1);
        assertThat(campaigns.get(0).platform()).isEqualTo("Яндекс Директ");
        assertThat(campaigns.get(0).latestVerdict()).isNull();
    }
}
