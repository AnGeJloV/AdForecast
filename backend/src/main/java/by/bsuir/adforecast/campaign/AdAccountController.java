package by.bsuir.adforecast.campaign;

import by.bsuir.adforecast.repo.AdAccountRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ad-accounts")
public class AdAccountController {

    private final AdAccountRepository adAccountRepository;

    public AdAccountController(AdAccountRepository adAccountRepository) {
        this.adAccountRepository = adAccountRepository;
    }

    @GetMapping
    public List<AdAccountResponse> list() {
        return adAccountRepository.findAllByOrderByIdAsc().stream()
                .map(account -> new AdAccountResponse(account.getId(), account.getAccountName(), account.getPlatform()))
                .toList();
    }
}
