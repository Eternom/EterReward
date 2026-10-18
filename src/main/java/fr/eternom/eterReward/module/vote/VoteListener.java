package fr.eternom.eterReward.module.vote;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** À l'arrivée sur un serveur : les votes reçus hors ligne et les clés de vote qui attendaient. */
public class VoteListener implements Listener {

    private final VoteService votes;

    public VoteListener(VoteService votes) {
        this.votes = votes;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        votes.welcome(event.getPlayer());
    }
}
