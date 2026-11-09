package fr.eternom.eterReward.module.pass;

import fr.eternom.eterSuccess.api.AchievementUnlockEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Un succès débloqué (EterSuccess) rapporte de l'XP de pass selon ses points (pass.success-xp-per-point).
 * Enregistré seulement si EterSuccess tourne ici (sa classe d'événement n'existe pas sinon).
 */
public class SuccessListener implements Listener {

    private final PassService pass;

    public SuccessListener(PassService pass) {
        this.pass = pass;
    }

    @EventHandler
    public void onUnlock(AchievementUnlockEvent event) {
        pass.successUnlocked(event.getPlayer(), event.getPoints());
    }
}
