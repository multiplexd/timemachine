/* See LICENSE file for license details */

package xyz.inaddr.timemachine;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.google.common.collect.UnmodifiableIterator;

import org.pircbotx.Channel;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ActionEvent;
import org.pircbotx.hooks.events.ConnectEvent;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.KickEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.NickChangeEvent;
import org.pircbotx.hooks.events.PartEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.events.QuitEvent;
import org.pircbotx.hooks.types.GenericChannelUserEvent;
import org.pircbotx.hooks.types.GenericMessageEvent;
import org.pircbotx.output.OutputIRC;

/**
 * Main bot event handler.
 */
public class TimeMachine extends ListenerAdapter {
    // substitution and recall commands -- credit to puck
    // (https://puckipedia.com) for these regexes.
    private final Pattern SED_MATCH = Pattern.compile("^[sS]/((?:\\\\/|[^/])*)(?!\\\\)/((?:\\\\/|[^/])*)/([^ ~]*)((?:~[0-9]+)?)");
    private final Pattern PRINT_MATCH = Pattern.compile("^[pP]/((?:\\\\/|[^/])*)(?!\\\\)/([^ ~]*)((?:~[0-9]+)?)");

    private final Pattern ADDRESSED_MATCH = Pattern.compile("^[^,: /]+[,:]\\s+.*$");

    // emulate the sound of the tardis when quitting
    private final String PART_MESSAGE = "*hooreeerwww... hooreeerwww... veeoom-eeom...*";

    private Set<String> ignorelist;
    private List<String> ownerlist;
    private final int recall_limit;
    private final String initmodes;
    private MessageLog log;

    public TimeMachine(int rl, Set<String> ig, List<String> ol, String m) {
        this.recall_limit = rl;
        this.ignorelist = ig;
        this.ownerlist = ol;
        this.initmodes = m;
        this.log = new MessageLog();
    }

    // administrative interface
    @Override
    public void onPrivateMessage(PrivateMessageEvent event) {
        String[] split;
        OutputIRC out;

        if (!isOwner(event.getUserHostmask().getHostmask()))
            return;

        split = event.getMessage().split("\\s+", 3);

        out = event.getBot().sendIRC();

        if (split[0].equals("quit")) {
            event.getBot().stopBotReconnect();
            // emulate the sound of the tardis when quitting
            out.quitServer(PART_MESSAGE);
            return;
        }

        if (split.length == 1)
            return;

        switch (split[0]) {
        case "join":
            if (split.length > 2) {
                out.joinChannel(split[1], split[2]);
            } else {
                out.joinChannel(split[1]);
            }
            break;
        case "part":
            if (event.getBot().getUserChannelDao().containsChannel(split[1])) {
                event.getBot()
                    .getUserChannelDao()
                    .getChannel(split[1])
                    .send()
                    .part(PART_MESSAGE);
            }
            break;
        case "ignore":
            this.ignorelist.add(split[1]);
            break;
        case "unignore":
            this.ignorelist.remove(split[1]);
            break;
        }

        return;
    }

    private boolean isOwner(String hostmask) {
        Pattern p;

        for (String owner: ownerlist) {
            try {
                p = Pattern.compile(owner);
                if (p.matcher(hostmask).matches())
                    return true;
            } catch (PatternSyntaxException pse) {
                // handled in Configurator
            }
        }

        return false;
    }
    
    @Override
    public void onConnect(ConnectEvent event) {
	// set user modes when connected
        event.getBot().sendIRC().mode(event.getBot().getNick(), this.initmodes);
    }

    @Override
    public void onMessage(MessageEvent event) {
        this.messageDriver(event, false);
    }

    @Override
    public void onAction(ActionEvent event) {
        this.messageDriver(event, true);
    }

    // handle public messages to channels 
    private <T extends GenericMessageEvent & GenericChannelUserEvent> void messageDriver(T event, boolean isctcp) {
        String channel, user, message, parsed, result;
        String[] split;
        ChannelHist chist;
        UserHist uhist;

        this.log.populateChannel(event.getChannel());

        channel = event.getChannel().getName();
        user = event.getUser().getNick();

        if (this.ignorelist.contains(user))
            return;

        chist = this.log.getChannel(channel);
        if (chist == null) return;

        uhist = chist.getUser(user);
        if (uhist == null) return;

        message = event.getMessage();
        parsed = message;

        // handle messages of the format "bob: s/foo/bar" by splitting into nick
        // and line, and then setting the default target of any possible recall
        // or search/replace to the addressed user.
        if (!isctcp && this.ADDRESSED_MATCH.matcher(message).matches()) {
            split = message.split("[:,]\\s+", 2);

            if (split.length > 1) {
                user = split[0];
                parsed = split[1];
            }
        }

        result = null;

        if ((result = this.searchReplace(chist, user, parsed)) != null) {
            event.getChannel().send().message(result);
        } else if ((result = this.recall(chist, user, parsed)) != null) {
            event.getChannel().send().message(result);
        }

        uhist.pushMsg(message, isctcp);
    }

    // recall a user's previous line
    private String recall(ChannelHist channel, String user, String message) {
        Matcher match;
        String search, target;
        int offset;
        UserHist uhist;
        String ret;

        match = this.PRINT_MATCH.matcher(message);
        if (!match.matches()) return null;

        search = match.group(1);
        target = match.group(2);

        if (target.equals("")) {
            target = user;
        } else {
            target = channel.expandUniquePrefix(target);
        }

        if (target == null) return null;

        uhist = channel.getUser(target);
        if (uhist == null) return null;

        if (match.group(3).equals("")) {
            offset = 0;
        } else {
            try {
                // remove leading tilde
                offset = Integer.parseUnsignedInt(match.group(3).substring(1));
            } catch (NumberFormatException nfe) {
                return null;
            }
        }

        ret = uhist.recall(search, offset);
        if (ret == null) return null;

        return String.format(ret, target);
    }

    // perform a regex search and replace on a user's line
    private String searchReplace(ChannelHist channel, String user, String message) {
        Matcher match;
        String search, replace, target;
        int offset;
        UserHist uhist;
        String ret;

        match = this.SED_MATCH.matcher(message);
        if (!match.matches()) return null;

        search = match.group(1);
        replace = match.group(2);
        target = match.group(3);

        if (target.equals("")) {
            target = user;
        } else {
            target = channel.expandUniquePrefix(target);
        }

        if (target == null) return null;

        uhist = channel.getUser(target);
        if (uhist == null) return null;

        if (match.group(4).equals("")) {
            offset = 0;
        } else {
            try {
                // remove leading tilde
                offset = Integer.parseUnsignedInt(match.group(4).substring(1));
            } catch (NumberFormatException nfe) {
                return null;
            }
        }

        ret = uhist.searchReplace(search, replace, offset);
        if (ret == null) return null;

        return String.format(ret, target);
    }

    @Override
    public void onJoin(JoinEvent event) {
        ChannelHist hist;
        
        if (event.getUser().getNick().equalsIgnoreCase(event.getBot().getNick())) {
            // if the join event is for this bot, then set up state for the
            // joined channel and return -- we need further events to occur
            // for the list of users on this channel to become available.
            this.log.addChannel(event.getChannel().getName());
            return;
        }

        this.log.populateChannel(event.getChannel());

        hist = this.log.getChannel(event.getChannel().getName());
        if (hist != null)
            hist.addUser(event.getUser().getNick());

        return;
    }

    @Override
    public void onPart(PartEvent event) {
        ChannelHist hist;

        if (event.getUser().getNick().equalsIgnoreCase(event.getBot().getNick())) {
            // we parted the channel
            this.log.delChannel(event.getChannel().getName());
            return;
        }

        this.log.populateChannel(event.getChannel());

        // remove user from state
        hist = this.log.getChannel(event.getChannel().getName());
        if (hist != null)
            hist.delUser(event.getUser().getNick());

        return;
    }

    @Override
    public void onKick(KickEvent event) {
        ChannelHist hist;

        if (event.getRecipient().getNick().equalsIgnoreCase(event.getBot().getNick())) {
            // we were kicked from the channel
            this.log.delChannel(event.getChannel().getName());
            return;
        }

        this.log.populateChannel(event.getChannel());

        // remove user from state
        hist = this.log.getChannel(event.getChannel().getName());
        if (hist != null)
            hist.delUser(event.getRecipient().getNick());
        return;
    }

    @Override
    public void onQuit(QuitEvent event) {
        if (event.getUser().getNick().equalsIgnoreCase(event.getBot().getNick()))
            // ignore our own quit messages
            return;

        // delete user from channel message logs
        this.log.deleteNick(event.getUser().getNick());

        return;
    }

    @Override
    public void onNickChange(NickChangeEvent event) {
        if (event.getOldNick().equalsIgnoreCase(event.getBot().getNick()) ||
            event.getNewNick().equalsIgnoreCase(event.getBot().getNick()))
            // ignore our own nick changes
            return;

	// change key under which user message history is stored
        this.log.channelNick(event.getOldNick(), event.getNewNick());

        return;
    }

    private class MessageLog {
        private Map<String, ChannelHist> history;

        MessageLog() {
            this.history = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        }

        void addChannel(String channel) {
            this.history.put(channel, new ChannelHist());
        }

        void delChannel(String channel) {
            this.history.remove(channel);
        }

        ChannelHist getChannel(String channel) {
            return this.history.get(channel);
        }

	// add users to channel from populated Channel object
        void populateChannel(Channel channel) {
            ChannelHist hist;
            UnmodifiableIterator<String> nicks;

            hist = this.history.get(channel.getName());
            if (hist == null) return;

            if (!hist.initialised()) {
                nicks = channel.getUsersNicks().iterator();
                while (nicks.hasNext()) {
                    hist.addUser(nicks.next());
                }
                hist.synced();
            }
        }

	// rekey channel history for all channels on nick change
        void channelNick(String oldnick, String newnick) {
            for (ChannelHist channel: this.history.values()) {
                channel.userNick(oldnick, newnick);
            }
        }

        // remove quitting users
        void deleteNick(String nick) {
            for (ChannelHist channel: this.history.values()) {
                channel.delUser(nick);
            }
        }
    }

    private class ChannelHist {
        private Map<String, UserHist> history;
        private boolean initialised;

        ChannelHist() {
            this.history = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            this.initialised = false;
        }

        boolean initialised() {
            // PircBotX will send JOIN events before it has received the names
            // list for a channel (353 messages), so we need to delay recording
            // existing users in the channel when we join until we receive other
            // messages on the channel

            return this.initialised;
        }

        void synced() {
            this.initialised = true;
        }

        void addUser(String nick) {
            this.history.put(nick, new UserHist());
        }

        void delUser(String nick) {
            this.history.remove(nick);
        }

        UserHist getUser(String nick) {
            return this.history.get(nick);
        }

        // search for username from unique prefix
        String expandUniquePrefix(String prefix) {
            String ret, nick;
            int matches;
            Iterator<String> users;

            matches = 0; ret = null;
            users = this.history.keySet().iterator();
            while (users.hasNext()) {
                nick = users.next();
		if (nick.equals(prefix)) {
		    // catch the special case where one user has a nick which is
		    // a substring of another's
		    ret = nick;
		    matches = 1;
		} else if (nick.startsWith(prefix)) {
                    ret = nick;
                    matches++;
                }
            }

            if (matches == 1) {
                return new String(ret);
            } else {
                return null;
            }
        }

	// change user nick if user is present
        void userNick(String oldnick, String newnick) {
            UserHist hist = this.history.remove(oldnick);
            if (hist != null)
                this.history.put(newnick, hist);

            return;
        }
    }

    private class UserHist {
        private final String PRIVMSGFMT = "<%%s%s> %s";
        private final String ACTIONFMT = "* %%s%s %s";
        private LinkedList<UserMsg> history;

        UserHist() {
            this.history = new LinkedList<>();
        }

        void pushMsg(String line, boolean ctcp) {
            UserMsg msg = new UserMsg(line, ctcp);
            this.history.addFirst(msg);

            if (this.history.size() > recall_limit)
                this.history.removeLast();
        }

        String searchReplace(String search, String replace, int offset) {
            String replacement, ret;
            UserMsg line;
            Pattern pat;
            StringBuilder stars;

            line = null; pat = null;

            try {
                pat = Pattern.compile(search);
            } catch (PatternSyntaxException pse) {
                return null;
            }

            for (int i = 0; i < this.history.size(); i++) {
                if (pat.matcher(this.history.get(i).topline()).find()) {
                    if (offset > 0) {
                        offset -= 1;
                    } else {
                        line = this.history.get(i);
                        break;
                    }
                }
            }

            if (line == null) return null;

            replacement = pat.matcher(line.topline()).replaceFirst(replace);
            stars = new StringBuilder();

            for (int i = 0; i < line.histsize(); i++)
                stars.append('*');

            ret = String.format(line.isctcp() ? this.ACTIONFMT : this.PRIVMSGFMT,
                                stars.toString(), replacement);

            line.pushline(replacement);

            return ret;
        }

        String recall(String search, int offset) {
            String recalled;
            UserMsg line;
            Pattern pat;
            StringBuilder stars;

            line = null; pat = null;

            try {
                pat = Pattern.compile(search);
            } catch (PatternSyntaxException pse) {
                return null;
            }

            for (int i = 0; i < this.history.size(); i++) {
                if (pat.matcher(this.history.get(i).topline()).find()) {
                    if (offset > 0) {
                        offset -= 1;
                    } else {
                        line = this.history.get(i);
                        break;
                    }
                }
            }

            if (line == null) return null;

            recalled = line.topline();
            stars = new StringBuilder();

            for (int i = 1; i < line.histsize(); i++)
                stars.append('*');

            return String.format(line.isctcp() ? this.ACTIONFMT : this.PRIVMSGFMT,
                                 stars.toString(), recalled);
        }
    
    }

    private class UserMsg {
        private LinkedList<String> lines;
        private final boolean isctcp;

        UserMsg(String line, boolean ctcp) {
            this.lines = new LinkedList<>();
            this.lines.addFirst(line);
            this.isctcp = ctcp;
        }

        void pushline(String line) {
            this.lines.addFirst(line);
        }

        boolean isctcp() {
            return this.isctcp;
        }

        String topline() {
            return this.lines.peek();
        }

        int histsize() {
            return this.lines.size();
        }
    }
}

