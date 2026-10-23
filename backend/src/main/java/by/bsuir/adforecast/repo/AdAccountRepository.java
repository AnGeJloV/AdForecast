package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.AdAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdAccountRepository extends JpaRepository<AdAccount, Long> {

    List<AdAccount> findAllByOrderByIdAsc();

    List<AdAccount> findByOrganizationIdOrderByIdAsc(Long organizationId);
}
