package fr.eternom.eterReward.listeners;

import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterReward.Main;
import fr.eternom.eterReward.module.challenge.ChallengeListener;
import fr.eternom.eterReward.module.daily.DailyListener;
import fr.eternom.eterReward.module.pass.MarketQuestListener;
import fr.eternom.eterReward.module.vote.VoteListener;
import fr.eternom.eterReward.module.pass.PassActivity;

public class Events {

    public Events(Main main) {
        main.getServer().getPluginManager().registerEvents(new DailyListener(main, main.getDaily()), main);

        PassActivity activity = new PassActivity(main.getPass(), main.getRanking()::playedMinute);
        main.getServer().getPluginManager().registerEvents(activity, main);
        activity.start();

        main.getServer().getPluginManager().registerEvents(new VoteListener(main.getVotes()), main);

        ChallengeListener challenges = new ChallengeListener(main.getChallenges());
        main.getServer().getPluginManager().registerEvents(challenges, main);

        // Quêtes de métier : seulement si EterMarket est là (sa classe d'événement n'existe pas sinon)
        if (main.getServer().getPluginManager().isPluginEnabled("EterMarket")) {
            main.getServer().getPluginManager().registerEvents(new MarketQuestListener(main.getPass(), challenges,
                    uuid -> Tasks.async(main, () -> main.getRanking().questCompleted(uuid),
                            "Quête non comptée au classement")), main);
        }
    }
}
