package fr.eternom.eterReward.module.pass;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temps de jeu ACTIF pour l'XP du pass : chaque minute, seuls les joueurs qui ont tourné la tête, marché, agi ou parlé
 * pendant la minute gagnent de l'XP. Un AFK immobile ne gagne rien ; un AFK « malin » (bassin d'eau...) est arrêté par
 * le plafond par jour, qui oblige à faire les quêtes pour finir le pass.
 */
public class PassActivity implements Listener {

    private static final long MINUTE_MILLIS = 60_000;

    private final PassService pass;
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();

    public PassActivity(PassService pass) {
        this.pass = pass;
    }

    /** Toutes les minutes (thread principal). */
    public void start() {
        Bukkit.getScheduler().runTaskTimer(pass.plugin(), this::minute, 20 * 60, 20 * 60);
    }

    private void minute() {
        long since = System.currentTimeMillis() - MINUTE_MILLIS;
        List<UUID> active = Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.hasPermission(PassService.PERMISSION))
                .map(Player::getUniqueId)
                .filter(uuid -> lastActive.getOrDefault(uuid, 0L) >= since)
                .toList();
        if (active.isEmpty() || !pass.enabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(pass.plugin(), () -> {
            for (UUID uuid : active) {
                try {
                    pass.playtime(uuid);
                } catch (RuntimeException e) {
                    pass.plugin().getLogger().warning("XP de temps de jeu non comptée pour " + uuid + " : " + e.getMessage());
                }
            }
        });
    }

    /** Seulement un vrai mouvement ou un regard : pas les petits recalages du serveur. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedBlock() || event.hasChangedOrientation()) {
            lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    /** Le palier du pass dans la sidebar, peu après l'arrivée. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(pass.plugin(), () -> {
            if (player.isOnline()) {
                pass.publishTag(player);
            }
        }, 40);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastActive.remove(event.getPlayer().getUniqueId());
    }
}
