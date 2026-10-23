package by.bsuir.adforecast.auth;

import java.util.List;

public record CurrentUserResponse(String login, String fullName, String role, String organization,
                                  List<String> permissions) {

    public static CurrentUserResponse of(String login, String fullName, String role, String organization,
                                         List<String> permissions) {
        return new CurrentUserResponse(login, fullName, role, organization, permissions);
    }
}
