package by.bsuir.adforecast.repo;

import by.bsuir.adforecast.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query("""
            select user
            from User user
            join fetch user.role
            where user.login = :login
            """)
    Optional<User> findByLogin(@Param("login") String login);
}
