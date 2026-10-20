package by.bsuir.adforecast.security;

import by.bsuir.adforecast.domain.User;
import by.bsuir.adforecast.repo.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {
        User user = userRepository.findByLogin(login)
                .orElseThrow(() -> new UsernameNotFoundException("пользователь не найден"));
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getLogin())
                .password(user.getPasswordHash())
                .disabled(!user.getIsActive())
                .build();
    }
}
