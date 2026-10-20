package by.bsuir.adforecast.auth;

import java.util.List;

public record CurrentUserResponse(String login, String role, List<String> permissions) {

    public static CurrentUserResponse of(String login, String role, List<String> permissions) {
        return new CurrentUserResponse(login, role, permissions);
    }
}
