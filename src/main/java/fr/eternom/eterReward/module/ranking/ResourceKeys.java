package fr.eternom.eterReward.module.ranking;

import fr.eternom.eterResource.api.ResourceApi;

import java.util.UUID;

/** Clés de ressource par l'API d'EterResource. Classe à part, utilisée seulement s'il est là. Bloquant. */
final class ResourceKeys {

    private ResourceKeys() {
    }

    static void give(UUID player, int keys) {
        ResourceApi.get().ifPresent(api -> api.giveKeys(player, keys));
    }
}
