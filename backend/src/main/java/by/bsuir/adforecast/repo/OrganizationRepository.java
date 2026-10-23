package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    Organization findFirstByOrderByIdAsc();
}
