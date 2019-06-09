/* See LICENSE file for license details */

package xyz.in_addr.timemachine;

import java.io.IOException;

import xyz.in_addr.timemachine.Configurator;
import xyz.in_addr.timemachine.TimeMachine;

import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.exception.IrcException;
import org.pircbotx.hooks.managers.SequentialListenerManager;

/**
 * Bot entry point.
 */
public class Main {
    public static void main (String[] args) throws IOException, IrcException, InterruptedException {
        Configurator.TMConfig config;
        Configuration.Builder builder;
        Configuration botconfig;
        SequentialListenerManager manager;
        TimeMachine machine;
        PircBotX ircbot;

        config = Configurator.loadConfig(args);
        builder = config.config;
        manager = SequentialListenerManager.builder().build();
        machine = new TimeMachine(config.recalllimit,
                                  config.ignorelist,
                                  config.ownerlist,
                                  config.initialmodes);

        // we use a sequential listener manager because the data structure
        // manipulation in TimeMachine is not thread-safe. this might be slow,
        // but it guarantees data consistency.
        manager.addListenerSequential(machine);
        botconfig = builder.setListenerManager(manager).buildConfiguration();

        ircbot = new PircBotX(botconfig);
        ircbot.startBot();

	// once the bot exits, give background threads some grace time then punt
	// out.
	Thread.sleep(5);
	System.exit(0);
    }
}
