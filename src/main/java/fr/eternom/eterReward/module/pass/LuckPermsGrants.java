package fr.eternom.eterReward.module.pass;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.data.TemporaryNodeMergeStrategy;
import net.luckperms.api.node.Node;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Permissions temporaires par LuckPerms (le joueur peut être hors ligne ou sur un autre serveur : LuckPerms les
 * partage). Classe à part, utilisée seulement si LuckPerms est présent (sinon NoClassDefFoundError).
 */
final class LuckPermsGrants {

    private LuckPermsGrants() {
    }

    /**
     * Pour une durée : une durée déjà en cours s'allonge (7 jours + 7 jours = 14 jours), elle ne repart pas de zéro.
     * Bloquant (attend LuckPerms) : hors du thread principal.
     */
    static void grantFor(UUID player, String permission, Duration duration) {
        modify(player, Node.builder(permission).expiry(duration).build(), TemporaryNodeMergeStrategy.ADD_NEW_DURATION_TO_EXISTING);
    }

    /** Jusqu'à until (ex : fin du mois du pass) ; garde la plus longue si elle existe déjà. Bloquant. */
    static void grantUntil(UUID player, String permission, Instant until) {
        modify(player, Node.builder(permission).expiry(until).build(), TemporaryNodeMergeStrategy.REPLACE_EXISTING_IF_DURATION_LONGER);
    }

    private static void modify(UUID player, Node node, TemporaryNodeMergeStrategy strategy) {
        CompletableFuture<Void> done = LuckPermsProvider.get().getUserManager()
                .modifyUser(player, user -> user.data().add(node, strategy));
        done.join();
    }
}
