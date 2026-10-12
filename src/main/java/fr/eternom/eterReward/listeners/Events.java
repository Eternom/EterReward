package fr.eternom.eterReward.listeners;

import fr.eternom.eterReward.Main;
import fr.eternom.eterReward.module.daily.DailyListener;
import fr.eternom.eterReward.module.pass.MarketQuestListener;
import fr.eternom.eterReward.module.pass.PassActivity;

public class Events {

    public Events(Main main) {
        main.getServer().getPluginManager().registerEvents(new DailyListener(main, main.getDaily()), main);

        PassActivity activity = new PassActivity(main.getPass());
        main.getServer().getPluginManager().registerEvents(activity, main);
        activity.start();
        // Quêtes de métier : seulement si EterMarket est là (sa classe d'événement n'existe pas sinon)
        if (main.getServer().getPluginManager().isPluginEnabled("EterMarket")) {
            main.getServer().getPluginManager().registerEvents(new MarketQuestListener(main.getPass()), main);
        }
    }
}
