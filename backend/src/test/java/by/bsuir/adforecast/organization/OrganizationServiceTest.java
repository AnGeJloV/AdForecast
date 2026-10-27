package by.bsuir.adforecast.organization;

import by.bsuir.adforecast.domain.AdAccount;
import by.bsuir.adforecast.domain.Organization;
import by.bsuir.adforecast.repo.AdAccountRepository;
import by.bsuir.adforecast.repo.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private AdAccountRepository adAccountRepository;

    @InjectMocks
    private OrganizationService service;

    @Test
    void createWithStandardAccountsCreatesAccountPerCatalogPlatform() {
        Organization organization = new Organization();
        organization.setId(5L);
        organization.setName("Организация «Новая»");
        when(organizationRepository.save(any(Organization.class))).thenReturn(organization);
        when(adAccountRepository.save(any(AdAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Organization created = service.createWithStandardAccounts("Организация «Новая»");

        assertThat(created.getName()).isEqualTo("Организация «Новая»");
        verify(adAccountRepository, times(PlatformCatalog.names().size())).save(any(AdAccount.class));
    }

    @Test
    void createAccountRequiresPlatformName() {
        Organization organization = new Organization();

        assertThatThrownBy(() -> service.createAccount(organization, "кабинет", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("укажите название площадки");
        assertThatThrownBy(() -> service.createAccount(organization, "кабинет", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createAccountSavesConnectedCustomAccount() {
        Organization organization = new Organization();
        when(adAccountRepository.save(any(AdAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdAccount created = service.createAccount(organization, "Мой кабинет", "Kufar");

        assertThat(created.getPlatform()).isEqualTo("Kufar");
        assertThat(created.getAccountName()).isEqualTo("Мой кабинет");
        assertThat(created.getOrganization()).isSameAs(organization);
        assertThat(created.getConnectedAt()).isNotNull();
    }

    @Test
    void platformCatalogCoversNineChannelsWithMinimums() {
        assertThat(PlatformCatalog.names()).hasSize(9);
        assertThat(PlatformCatalog.find("TikTok Ads"))
                .hasValueSatisfying(info -> assertThat(info.minDayBudget()).isEqualByComparingTo("148"));
        assertThat(PlatformCatalog.find("Email-платформа"))
                .hasValueSatisfying(info -> assertThat(info.minDayBudget().signum()).isZero());
        assertThat(PlatformCatalog.isKnown("Авито")).isFalse();
    }
}
