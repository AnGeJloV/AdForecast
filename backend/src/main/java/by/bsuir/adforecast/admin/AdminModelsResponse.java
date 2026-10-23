package by.bsuir.adforecast.admin;

import java.util.List;

/**
 * Версии общей модели управляет администратор. Модели организаций обучаются
 * автоматически, в админке показываются только счётчиками.
 */
public record AdminModelsResponse(
        List<AdminModelResponse> models,
        long organizationsTotal,
        long organizationsWithOwnModel
) {
}
