package fr.eternom.eterReward.module;

import fr.eternom.eterSync.api.SyncApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Attend qu'EterSync ait chargé l'inventaire d'un joueur avant d'y mettre des objets (sinon ils seraient écrasés).
 * EterSync en softdepend : sa classe n'est touchée que s'il tourne ici ; sans lui, tout de suite.
 */
public final class SyncWait {

    /** Vérifié toutes les demi-secondes, 30 s au plus. */
    private static final int CHECKS = 60;

    private SyncWait() {
    }

    /** Thread principal. action n'est lancée que si le joueur est encore là. */
    public static void whenLoaded(JavaPlugin plugin, Player player, Runnable action) {
        int[] checks = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || ++checks[0] > CHECKS) {
                task[0].cancel();
            } else if (!loading(player)) {
                task[0].cancel();
                action.run();
            }
        }, 10, 10);
    }

    private static boolean loading(Player player) {
        return Bukkit.getPluginManager().isPluginEnabled("EterSync")
                && SyncApi.get().map(sync -> sync.isLoading(player.getUniqueId())).orElse(false);
    }
}
