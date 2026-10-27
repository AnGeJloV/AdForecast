package by.bsuir.adforecast.security;

import by.bsuir.adforecast.domain.Organization;
import by.bsuir.adforecast.domain.Role;
import by.bsuir.adforecast.domain.User;
import by.bsuir.adforecast.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AppUserDetailsService service;

    private User user(boolean active) {
        Role role = new Role();
        role.setRoleName("маркетолог");
        User user = new User();
        user.setLogin("marketer");
        user.setPasswordHash("bcrypt-hash");
        user.setIsActive(active);
        user.setRole(role);
        user.setOrganization(new Organization());
        return user;
    }

    @Test
    void activeUserIsLoadedWithPasswordHash() {
        when(userRepository.findByLogin("marketer")).thenReturn(Optional.of(user(true)));

        UserDetails details = service.loadUserByUsername("marketer");

        assertThat(details.getUsername()).isEqualTo("marketer");
        assertThat(details.getPassword()).isEqualTo("bcrypt-hash");
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void blockedUserIsDisabled() {
        when(userRepository.findByLogin("marketer")).thenReturn(Optional.of(user(false)));

        assertThat(service.loadUserByUsername("marketer").isEnabled()).isFalse();
    }

    @Test
    void unknownLoginIsRejected() {
        when(userRepository.findByLogin("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
