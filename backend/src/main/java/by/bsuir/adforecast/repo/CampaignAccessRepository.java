package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.CampaignAccess;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignAccessRepository extends JpaRepository<CampaignAccess, Long> {

    boolean existsByUserIdAndCampaignId(Long userId, Long campaignId);
}
