package fr.eternom.eterReward.module.pass;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.module.activity.Activity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Temps de jeu ACTIF pour l'XP du pass : chaque minute, seuls les joueurs qui ont tourné la tête, marché, agi ou parlé
 * pendant la minute gagnent de l'XP (multipliée dans certains mondes : pass.world-xp). Un AFK immobile ne gagne rien ;
 * un AFK « malin » est arrêté par le plafond par jour. Toutes les minutes du serveur partent en une seule écriture.
 * Les mêmes minutes, si le joueur a aussi regardé autour de lui ou parlé, comptent au classement du temps de jeu.
 * L'activité elle-même est mesurée par EterLib (Activity), la même pour tous les plugins.
 */
public class PassActivity implements Listener {

    private static final long MINUTE_MILLIS = 60_000;

    private final PassService pass;
    /** Chaque minute active, aussi pour le classement du temps de jeu (tâche de fond, bloquant). */
    private final Consumer<UUID> onActiveMinute;

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
        Activity activity = EterLib.get().getActivity();
        boolean passEnabled = pass.enabled();
        int perMinute = pass.settings().playtimeXpPerMinute();
        List<PassService.Minute> minutes = new ArrayList<>();
        List<UUID> looking = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (!player.hasPermission(PassService.PERMISSION) || !activity.activeSince(uuid, since)) {
                continue;
            }
            pass.refreshBoost(player);
            int amount = (int) Math.round(perMinute * pass.settings().worldMultiplier(player.getWorld().getName()));
            if (amount > 0) {
                minutes.add(new PassService.Minute(uuid, amount));
            }
            // Le classement du temps de jeu n'a pas de plafond : seulement les minutes où le joueur a regardé ou parlé
            if (activity.lookedSince(uuid, since)) {
                looking.add(uuid);
            }
        }
        if (minutes.isEmpty() && looking.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(pass.plugin(), () -> {
            if (passEnabled) {
                try {
                    pass.playtime(minutes);
                } catch (RuntimeException e) {
                    pass.plugin().getLogger().warning("Temps de jeu du pass non compté : " + e.getMessage());
                }
            }
            for (UUID uuid : looking) {
                try {
                    onActiveMinute.accept(uuid);
                } catch (RuntimeException e) {
                    pass.plugin().getLogger().warning("Temps de jeu non compté au classement pour " + uuid + " : " + e.getMessage());
                }
            }
        });
    }

    /** Arrivée : booster, sidebar, rappels (paliers à réclamer, mois d'avant, colis). */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        pass.joined(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pass.forget(event.getPlayer().getUniqueId());
    }
}
