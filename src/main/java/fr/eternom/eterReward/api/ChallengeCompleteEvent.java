package fr.eternom.eterReward.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Un défi du jour ou de la semaine vient d'être validé (thread principal), une seule fois : la base l'a enregistré et la
 * récompense est donnée. Pour les autres plugins (ex : les succès d'EterSuccess).
 */
public class ChallengeCompleteEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String challenge;
    private final boolean weekly;

    public ChallengeCompleteEvent(Player player, String challenge, boolean weekly) {
        super(player);
        this.challenge = challenge;
        this.weekly = weekly;
    }

    /** Identifiant du défi (challenges.yml). */
    public String getChallenge() {
        return challenge;
    }

    /** true pour un défi de la semaine, false pour un défi du jour. */
    public boolean isWeekly() {
        return weekly;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
