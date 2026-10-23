package by.bsuir.adforecast.organization;

import by.bsuir.adforecast.domain.AdAccount;
import by.bsuir.adforecast.domain.Organization;
import by.bsuir.adforecast.repo.AdAccountRepository;
import by.bsuir.adforecast.repo.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Новая организация получает стандартный набор рекламных кабинетов по каталогу площадок
 * — иначе в пустом аккаунте нельзя создать ни одной кампании. Кабинет на новую,
 * незнакомую модели площадку тоже можно завести: прогноз по ней честно скажет «нет данных».
 */
@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final AdAccountRepository adAccountRepository;

    public OrganizationService(OrganizationRepository organizationRepository,
                               AdAccountRepository adAccountRepository) {
        this.organizationRepository = organizationRepository;
        this.adAccountRepository = adAccountRepository;
    }

    @Transactional
    public Organization createWithStandardAccounts(String name) {
        Organization organization = new Organization();
        organization.setName(name);
        organization.setCreatedAt(LocalDateTime.now());
        Organization saved = organizationRepository.save(organization);
        for (String platform : PlatformCatalog.names()) {
            createAccount(saved, platform + "_кабинет", platform);
        }
        return saved;
    }

    /**
     * Кабинет можно завести на любую площадку, включая новую: по незнакомому каналу
     * прогноз честно скажет «нет данных», а своя модель обучится, когда появятся
     * завершённые кампании на этом канале.
     */
    @Transactional
    public AdAccount createAccount(Organization organization, String accountName, String platform) {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("укажите название площадки");
        }
        AdAccount account = new AdAccount();
        account.setOrganization(organization);
        account.setAccountName(accountName);
        account.setPlatform(platform);
        account.setConnectedAt(LocalDateTime.now());
        return adAccountRepository.save(account);
    }
}
