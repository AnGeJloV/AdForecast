package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByRoleName(String roleName);

    List<Role> findAllByOrderByIdAsc();
}
