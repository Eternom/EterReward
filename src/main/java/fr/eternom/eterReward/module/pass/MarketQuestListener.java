package fr.eternom.eterReward.module.pass;

import fr.eternom.eterMarket.api.JobQuestCompleteEvent;
import fr.eternom.eterReward.module.challenge.ChallengeListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Quêtes de métier d'EterMarket : XP du pass et défis « quêtes ». Classe à part, enregistrée seulement si EterMarket
 * est présent (sinon NoClassDefFoundError).
 */
public class MarketQuestListener implements Listener {

    private final PassService pass;
    private final ChallengeListener challenges;

    private final java.util.function.Consumer<java.util.UUID> onQuest;

    /** onQuest : en tâche de fond, le classement des quêtes du mois. */
    public MarketQuestListener(PassService pass, ChallengeListener challenges, java.util.function.Consumer<java.util.UUID> onQuest) {
        this.onQuest = onQuest;
        this.pass = pass;
        this.challenges = challenges;
    }

    @EventHandler
    public void onQuest(JobQuestCompleteEvent event) {
        pass.questCompleted(event.getPlayer(), event.getJob(), event.isBonus() ? "bonus" : event.getTier());
        challenges.onQuest(event.getPlayer());
        onQuest.accept(event.getPlayer().getUniqueId());
    }
}
