package fr.eternom.eterReward.module.daily;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Rappelle la récompense du jour peu après l'arrivée (pas noyée dans les messages de connexion). */
public class DailyListener implements Listener {

    private static final long DELAY_TICKS = 3 * 20;

    private final JavaPlugin plugin;
    private final DailyService daily;

    public DailyListener(JavaPlugin plugin, DailyService daily) {
        this.plugin = plugin;
        this.daily = daily;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(DailyService.PERMISSION)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    daily.remind(player);
                }
            }, DELAY_TICKS);
        }
    }
}
