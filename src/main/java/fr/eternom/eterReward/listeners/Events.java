package fr.eternom.eterReward.listeners;

import fr.eternom.eterReward.Main;
import fr.eternom.eterReward.module.daily.DailyListener;

public class Events {

    public Events(Main main) {
        main.getServer().getPluginManager().registerEvents(new DailyListener(main, main.getDaily()), main);
    }
}
