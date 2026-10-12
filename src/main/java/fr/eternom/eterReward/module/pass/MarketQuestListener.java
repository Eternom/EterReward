package fr.eternom.eterReward.module.pass;

import fr.eternom.eterMarket.api.JobQuestCompleteEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * XP du pass pour les quêtes de métier d'EterMarket. Classe à part, enregistrée seulement si EterMarket est présent
 * (sinon NoClassDefFoundError).
 */
public class MarketQuestListener implements Listener {

    private final PassService pass;

    public MarketQuestListener(PassService pass) {
        this.pass = pass;
    }

    @EventHandler
    public void onQuest(JobQuestCompleteEvent event) {
        pass.questCompleted(event.getPlayer(), event.getJob(), event.isBonus() ? "bonus" : event.getTier());
    }
}
