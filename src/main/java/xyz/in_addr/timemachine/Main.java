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
    public static void main (String[] args) {
        Configurator.TMConfig config;
        Configuration.Builder builder;
        Configuration botconfig;
        TimeMachine machine;
        PircBotX bot;

        config = Configurator.loadConfig(args);

        builder = config.config;
        machine = new TimeMachine(config.recalllimit, config.ignorelist,
                                  config.ownerlist, config.initialmodes);
        botconfig = builder.addListener(machine).buildConfiguration();
        bot = new PircBotX(botconfig);

        try {
            bot.startBot();
        } catch (IrcException ie) {
            System.err.println("Encountered IRC exception: " + ie.getMessage());
            System.exit(1);
        } catch (IOException ioe) {
            System.err.println("Encountered IO exception: " + ioe.getMessage());
            System.exit(1);
        }

        System.exit(0);
    }
}

