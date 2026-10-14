package fr.eternom.eterReward.module;

import fr.eternom.eterResource.api.ResourceApi;

import java.util.UUID;

/**
 * Clés et temps de monde ressource, par l'API d'EterResource. Classe à part, utilisée seulement si EterResource est
 * présent (sinon NoClassDefFoundError). Bloquant (base).
 */
public final class ResourceRewards {

    private ResourceRewards() {
    }

    /** false si EterResource ne tourne pas sur ce serveur (la récompense n'est alors pas donnée). */
    public static boolean give(UUID player, int keys, int minutes) {
        ResourceApi api = ResourceApi.get().orElse(null);
        if (api == null) {
            return false;
        }
        if (keys > 0) {
            api.giveKeys(player, keys);
        }
        if (minutes > 0) {
            api.addTime(player, minutes * 60_000L);
        }
        return true;
    }
}
