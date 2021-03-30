/* See LICENSE file for license details */

package xyz.in_addr.timemachine;

import java.util.List;
import java.util.Set;

import java.util.regex.Pattern;

import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ConnectEvent;
import org.pircbotx.hooks.events.InviteEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.types.GenericUserEvent;
import org.pircbotx.output.OutputIRC;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listener for miscellaneous admin things.
 */
public class AdminListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(AdminListener.class);

    // emulate the sound of the tardis when quitting
    private final String PART_MESSAGE = "*hooreeerwww... hooreeerwww... veeoom-eeom...*";

    private final Set<String> ignorelist;
    private final List<Pattern> ownerlist;
    private final String initmodes;

    public AdminListener(Set<String> ignores, List<Pattern> owners, String modes) {
        this.ignorelist = ignores;
        this.ownerlist = owners;
        this.initmodes = modes;

        log.info("Admin listener initialised");
    }

    // administrative interface
    private void ownerInterface(GenericUserEvent event, String hostmask, String msg) {
        String[] split;
        OutputIRC out;

        if (!isOwner(hostmask)) {
            log.warn("Ignoring owner command received from non-owner: {}", hostmask);
            return;
        }

        log.info("Received owner command \"{}\" from: {}", msg, hostmask);

        split = msg.split("\\s+", 3);
        out = event.getBot().sendIRC();

        if (split[0].equals("quit")) {
            log.info("Quitting from server");

            event.getBot().stopBotReconnect();
            // emulate the sound of the tardis when quitting
            out.quitServer(PART_MESSAGE);
            return;
        }

        if (split.length == 1)
            return;

        switch (split[0]) {
        case "join":
            log.info("Joining {}...", split[1]);

            if (split.length > 2) {
                out.joinChannel(split[1], split[2]);
            } else {
                out.joinChannel(split[1]);
            }
            break;
        case "part":
            if (event.getBot().getUserChannelDao().containsChannel(split[1])) {
                log.info("Leaving {}...", split[1]);

                event.getBot()
                    .getUserChannelDao()
                    .getChannel(split[1])
                    .send()
                    .part(PART_MESSAGE);
            }
            break;
        case "ignore":
            log.info("Adding {} to ignore list.", split[1]);

            this.ignorelist.add(split[1]);
            break;
        case "unignore":
            log.info("Removing {} from the ignore list.", split[1]);

            this.ignorelist.remove(split[1]);
            break;
        case "say":
            if (split.length > 2 &&
                    event.getBot().getUserChannelDao().containsChannel(split[1])) {
                log.info("Sockpuppeting on channel {}", split[1]);

                event.getBot().getUserChannelDao()
                    .getChannel(split[1]).send().message(split[2]);
            }
            break;
        }

        return;
    }

    private boolean isOwner(String hostmask) {
        for (Pattern owner: ownerlist) {
            if (owner.matcher(hostmask).matches())
                return true;
        }

        return false;
    }

    @Override
    public void onConnect(ConnectEvent event) {
        // set user modes when connected
        if (this.initmodes != null) {
            log.info("Setting initial user modes: {}", this.initmodes);

            event.getBot().sendIRC().mode(event.getBot().getNick(), this.initmodes);
        }
    }

    @Override
    public void onPrivateMessage(PrivateMessageEvent event) {
        this.ownerInterface(event, event.getUserHostmask().getHostmask(), event.getMessage());
    }

    @Override
    public void onMessage(MessageEvent event) {
        String[] split;

        if (this.ignorelist.contains(event.getUser().getNick()))
            return;

        split = event.getMessage().split("[:;,]\\s+", 2);

        if (split.length > 1 && split[0].equalsIgnoreCase(event.getBot().getNick())) {
            this.ownerInterface(event, event.getUserHostmask().getHostmask(), split[1]);
        }
    }

    @Override
    public void onInvite(InviteEvent event) {
        OutputIRC out;
        String hostmask;

        hostmask = event.getUserHostmask().getHostmask();

        if (!isOwner(hostmask)) {
            log.warn("Ignoring invite from non-owner: {}", hostmask);
            return;
        }

        log.info("Accepting invite from: {}", hostmask);

        out = event.getBot().sendIRC();
        out.joinChannel(event.getChannel());
    }
}
