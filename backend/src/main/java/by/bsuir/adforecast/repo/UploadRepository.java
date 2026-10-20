package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.Upload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UploadRepository extends JpaRepository<Upload, Long> {

    List<Upload> findByCampaignIdOrderByIdDesc(long campaignId);
}
