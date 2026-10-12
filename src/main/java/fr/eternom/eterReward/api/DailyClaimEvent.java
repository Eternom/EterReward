package fr.eternom.eterReward.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * La récompense du jour vient d'être réclamée (thread principal), une seule fois : day = le jour de la série (1 à 7 ;
 * 7 = une série complète). Pour les autres plugins (succès...).
 */
public class DailyClaimEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int day;

    public DailyClaimEvent(Player player, int day) {
        super(player);
        this.day = day;
    }

    /** Jour de la série (1 à 7). */
    public int getDay() {
        return day;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
