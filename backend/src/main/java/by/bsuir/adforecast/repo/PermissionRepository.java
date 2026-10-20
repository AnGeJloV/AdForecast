package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    List<Permission> findByRoleId(Long roleId);
}
