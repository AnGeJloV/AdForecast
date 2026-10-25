package by.bsuir.adforecast.admin;

import java.util.List;

public record AdminActivityResponse(long total, List<Group> groups) {

    public record Group(String category, String label, long count) {
    }
}
