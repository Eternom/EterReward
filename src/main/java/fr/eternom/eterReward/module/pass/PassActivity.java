package fr.eternom.eterReward.module.pass;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.module.activity.Activity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Temps de jeu ACTIF pour l'XP du pass : chaque minute, seuls les joueurs qui ont tourné la tête, marché, agi ou parlé
 * pendant la minute gagnent de l'XP. Un AFK immobile ne gagne rien ; un AFK « malin » (bassin d'eau...) est arrêté par
 * le plafond par jour, qui oblige à faire les quêtes pour finir le pass.
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
        List<UUID> active = Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.hasPermission(PassService.PERMISSION))
                .map(Player::getUniqueId)
                .filter(uuid -> activity.activeSince(uuid, since))
                .toList();
        if (active.isEmpty()) {
            return;
        }
        boolean passEnabled = pass.enabled();
        // Le classement du temps de jeu n'a pas de plafond : seulement les minutes où le joueur a regardé ou parlé
        List<UUID> looking = active.stream().filter(uuid -> activity.lookedSince(uuid, since)).toList();
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
}
