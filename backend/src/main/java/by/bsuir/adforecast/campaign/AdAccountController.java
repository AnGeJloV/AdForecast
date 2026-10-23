package by.bsuir.adforecast.campaign;

import by.bsuir.adforecast.domain.AdAccount;
import by.bsuir.adforecast.domain.User;
import by.bsuir.adforecast.organization.OrganizationService;
import by.bsuir.adforecast.organization.PlatformCatalog;
import by.bsuir.adforecast.repo.AdAccountRepository;
import by.bsuir.adforecast.repo.UserRepository;
import by.bsuir.adforecast.security.AuthPrincipal;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ad-accounts")
public class AdAccountController {

    private final AdAccountRepository adAccountRepository;
    private final UserRepository userRepository;
    private final OrganizationService organizationService;

    public AdAccountController(AdAccountRepository adAccountRepository,
                               UserRepository userRepository,
                               OrganizationService organizationService) {
        this.adAccountRepository = adAccountRepository;
        this.userRepository = userRepository;
        this.organizationService = organizationService;
    }

    @GetMapping
    public List<AdAccountResponse> list(Authentication authentication) {
        return adAccountRepository.findByOrganizationIdOrderByIdAsc(currentUser(authentication).getOrganization().getId())
                .stream()
                .map(account -> new AdAccountResponse(account.getId(), account.getAccountName(), account.getPlatform()))
                .toList();
    }

    /** Каталог площадок с минимальным дневным бюджетом и ссылкой на источник. */
    @GetMapping("/platforms")
    public List<PlatformCatalog.PlatformInfo> platforms() {
        return PlatformCatalog.all();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdAccountResponse create(@Valid @RequestBody AdAccountCreateRequest request,
                                    Authentication authentication) {
        AdAccount account = organizationService.createAccount(
                currentUser(authentication).getOrganization(),
                request.accountName().trim(),
                request.platform().trim());
        return new AdAccountResponse(account.getId(), account.getAccountName(), account.getPlatform());
    }

    private User currentUser(Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return userRepository.findById(principal.userId())
                .orElseThrow(() -> new EntityNotFoundException("пользователь не найден"));
    }

    public record AdAccountCreateRequest(
            @NotBlank @Size(max = 255) String accountName,
            @NotBlank @Size(max = 100) String platform
    ) {
    }
}
