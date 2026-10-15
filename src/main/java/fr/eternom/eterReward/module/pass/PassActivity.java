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
import java.util.function.Consumer;

/**
 * Temps de jeu ACTIF pour l'XP du pass : chaque minute, seuls les joueurs qui ont tourné la tête, marché, agi ou parlé
 * pendant la minute gagnent de l'XP. Un AFK immobile ne gagne rien ; un AFK « malin » (bassin d'eau...) est arrêté par
 * le plafond par jour, qui oblige à faire les quêtes pour finir le pass.
 * Les mêmes minutes, si le joueur a aussi regardé autour de lui ou parlé, comptent au classement du temps de jeu.
 */
public class PassActivity implements Listener {

    private static final long MINUTE_MILLIS = 60_000;

    private final PassService pass;
    /** Chaque minute active, aussi pour le classement du temps de jeu (tâche de fond, bloquant). */
    private final Consumer<UUID> onActiveMinute;
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    /**
     * Dernier regard ou message : le classement du temps de jeu n'a pas de plafond, il ne compte donc que les minutes où
     * le joueur a tourné la tête ou parlé (un courant d'eau ou un wagonnet en boucle déplace, mais ne fait pas regarder).
     */
    private final Map<UUID, Long> lastLook = new ConcurrentHashMap<>();

    public PassActivity(PassService pass, Consumer<UUID> onActiveMinute) {
        this.pass = pass;
        this.onActiveMinute = onActiveMinute;
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
        if (active.isEmpty()) {
            return;
        }
        boolean passEnabled = pass.enabled();
        List<UUID> looking = active.stream().filter(uuid -> lastLook.getOrDefault(uuid, 0L) >= since).toList();
        Bukkit.getScheduler().runTaskAsynchronously(pass.plugin(), () -> {
            for (UUID uuid : active) {
                try {
                    if (passEnabled) {
                        pass.playtime(uuid);
                    }
                    if (looking.contains(uuid)) {
                        onActiveMinute.accept(uuid);
                    }
                } catch (RuntimeException e) {
                    pass.plugin().getLogger().warning("Temps de jeu non compté pour " + uuid + " : " + e.getMessage());
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
        if (event.hasChangedOrientation()) {
            lastLook.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        lastLook.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    /** Le palier du pass dans la sidebar, peu après l'arrivée ; et les récompenses réclamées juste avant un départ. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        pass.deliverPending(player);
        Bukkit.getScheduler().runTaskLater(pass.plugin(), () -> {
            if (player.isOnline()) {
                pass.publishTag(player);
            }
        }, 40);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastActive.remove(event.getPlayer().getUniqueId());
        lastLook.remove(event.getPlayer().getUniqueId());
    }
}
