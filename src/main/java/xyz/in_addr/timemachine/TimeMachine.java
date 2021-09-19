/* See LICENSE file for license details */

package xyz.in_addr.timemachine;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.google.common.collect.UnmodifiableIterator;

import org.pircbotx.Channel;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ActionEvent;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.KickEvent;
import org.pircbotx.hooks.events.ListenerExceptionEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.NickChangeEvent;
import org.pircbotx.hooks.events.PartEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.events.QuitEvent;
import org.pircbotx.hooks.types.GenericChannelUserEvent;
import org.pircbotx.hooks.types.GenericMessageEvent;
import org.pircbotx.hooks.types.GenericUserEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main bot event handler.
 */
public class TimeMachine extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(TimeMachine.class);

    // substitution and recall commands. the regexen here were originally
    // ones shared by Puck Meerburg, which i subsequently hacked on to adapt for
    // my own use. i then spent an inordinate amount of effort debacktrackifying
    // them for compatibility with google's re2j library. Puck still writes better
    // regexes than i do (and is otherwise winning the sedbot arms race), so these
    // are a more recent version of her regexes which support arbitrary input
    // delimiters, lightly modified to preserve existing behaviour. the code which
    // *really* needs re2j's linear-time matching now indirects through a wrapper
    // class to prevent clashing imports, and we default to the standard library's
    // backtracking implementation otherwise. matching regex with regex is hard.
    private final Pattern SED_MATCH = Pattern.compile("^[sS](\\W)((?:\\\\\\1|(?:(?!\\1).))*)(?!\\\\)\\1((?:\\\\\\1|\\\\\\\\|(?:(?!\\1).))*)(?:\\1([^ ~]*)((?:~[0-9]+)?))?");
    private final Pattern PRINT_MATCH = Pattern.compile("^[pP](\\W)((?:\\\\\\1|(?:(?!\\1).))*)(?!\\\\)\\1([^ ~]*)((?:~[0-9]+)?)");

    private final Pattern ADDRESSED_MATCH = Pattern.compile("^[^,:\\s/]+[,:]\\s+");
    private final String SOURCE_URL = "https://github.com/multiplexd/timemachine"; // self documentation

    private final Pattern BOTSNACK_MATCH = Pattern.compile("^\\s*botsnack\\s*$");
    private final String BOTSNACK_RESPONSE = ":D";

    private final ReentrantLock ignorelock;
    private final ReentrantLock loglock;
    private final Set<String> ignorelist;
    private final int recall_limit;
    private final MessageLog mlog;

    public TimeMachine(int limit, Set<String> ignores) {
        this.recall_limit = limit;
        this.ignorelist = ignores; /* this is assumed to be thread-safe */
        this.mlog = new MessageLog();

        this.ignorelock = new ReentrantLock(true);
        this.loglock = new ReentrantLock(true);

        log.info("Time machine is initialised. Vworp vworp!");
    }

    @Override
    public void onMessage(MessageEvent event) {
        this.loglock.lock();
        this.messageDriver(event, false);
        this.loglock.unlock();
    }

    @Override
    public void onAction(ActionEvent event) {
        this.loglock.lock();
        this.messageDriver(event, true);
        this.loglock.unlock();
    }

    // handle public messages to channels 
    private <T extends GenericMessageEvent & GenericChannelUserEvent> void messageDriver(T event, boolean isctcp) {
        String channel, user, message, parsed;
        String[] split;
        ChannelHist chist;
        UserHist uhist;
        Optional<String> result;

        this.mlog.populateChannel(event.getChannel());

        channel = event.getChannel().getName();
        user = event.getUser().getNick();

        if (this.ignorelist.contains(user))
            return;

        chist = this.mlog.getChannel(channel);
        if (chist == null) return;

        uhist = chist.getUser(user);
        if (uhist == null) return;

        message = event.getMessage();
        parsed = message;

        result = null;

        if (!isctcp && this.BOTSNACK_MATCH.matcher(message).matches()) {
            // standard #xkcd botsnack protocol response
            result = Optional.of(this.BOTSNACK_RESPONSE);
            log.info("Sending botsnack response");
        } else if (!isctcp && this.ADDRESSED_MATCH.matcher(message).find()) {
            // handle messages of the format "bob: s/foo/bar" by splitting into
            // nick and line, and then setting the default target of any possible
            // recall or search/replace to the addressed user.
            split = message.split("[:;,]\\s+", 2);

            if (split.length > 1) {
                user = split[0];
                parsed = split[1];

                if (user.equalsIgnoreCase(event.getBot().getNick()) &&
                        (parsed.equals("source") || parsed.equals("docs"))) {
                    // check for magic commands to return source URL
                    result = Optional.of(this.SOURCE_URL);
                    log.info("Sending source and docs URLs");
                }
            }
        }

        if (result == null) {
            result = this.searchReplace(chist, user, parsed);
        }

        if (result == null) {
            result = this.recall(chist, user, parsed);
        }

        // do not add messages which activate a trigger to the user's message
        // history. doing so makes it difficult and confusing to perform repeated
        // edits.
        if (result != null) {
            result.ifPresent((str) -> event.getChannel().send().message(str));
        } else {
            uhist.pushMsg(message, isctcp);
        }
    }

    // recall a user's previous line
    private Optional<String> recall(ChannelHist channel, String user, String message) {
        Matcher match;
        String delim, search, target, offstring;
        int offset;
        UserHist uhist;
        String ret;

        match = this.PRINT_MATCH.matcher(message);
        if (!match.find()) return null;

        delim = match.group(1);

        /* guard against invalid backslash and space separators */
        if (delim.equals("\\") || delim.equals(" ")) {
            return Optional.empty();
        }

        log.info("Recall command triggered");

        search = match.group(2).replace("\\" + delim, delim);
        target = match.group(3);
        offstring = match.group(4);

        if (target.equals("")) {
            target = user;
        } else {
            target = channel.expandUniquePrefix(target);
        }

        if (target == null) return Optional.empty();

        uhist = channel.getUser(target);
        if (uhist == null) return Optional.empty();

        if (offstring.equals("")) {
            offset = 0;
        } else {
            try {
                // remove leading tilde
                offset = Integer.parseUnsignedInt(offstring.substring(1));
            } catch (NumberFormatException nfe) {
                return Optional.empty();
            }
        }

        ret = uhist.recall(target, search, offset);
        if (ret == null) return Optional.empty();

        log.info("Recall command matched, returning result");

        return Optional.of(ret);
    }

    // perform a regex search and replace on a user's line
    private Optional<String> searchReplace(ChannelHist channel, String user, String message) {
        Matcher match;
        String delim, search, replace, target, offstring;
        int offset;
        boolean global;
        UserHist uhist;
        String ret;

        /* replace the first occurence or replace all of them? */
        global = false;

        match = this.SED_MATCH.matcher(message);
        if (!match.find()) return null;

        delim = match.group(1);

        if (delim.equals("\\") || delim.equals(" ")) {
            return Optional.empty();
        }

        log.info("Search and replace command triggered");

        search = match.group(2).replace("\\" + delim, delim);
        replace = match.group(3).replace("\\" + delim, delim);
        target = match.group(4);
        offstring = match.group(5);

        if (target == null && offstring == null && replace.equals("")) {
            // catch s/foo/ form
            return Optional.empty();
        }

        if (target == null || target.equals("")) {
            target = user;
        } else if (target.equals("g")) {
            target = user;
            global = true;
        } else {
            target = channel.expandUniquePrefix(target);
        }

        if (target == null) return Optional.empty();

        uhist = channel.getUser(target);
        if (uhist == null) return Optional.empty();

        if (offstring == null || offstring.equals("")) {
            offset = 0;
        } else {
            try {
                // remove leading tilde
                offset = Integer.parseUnsignedInt(offstring.substring(1));
            } catch (NumberFormatException nfe) {
                return Optional.empty();
            }
        }

        ret = uhist.searchReplace(target, search, replace, offset, global);
        if (ret == null) return Optional.empty();

        log.info("Search and replace command matched, returning result");

        return Optional.of(ret);
    }


    @Override
    public void onJoin(JoinEvent event) {
        ChannelHist hist;

        this.loglock.lock();

        if (event.getUser().getNick().equalsIgnoreCase(event.getBot().getNick())) {
            // if the join event is for this bot, then set up state for the
            // joined channel and return -- we need further events to occur
            // for the list of users on this channel to become available.
            this.mlog.addChannel(event.getChannel().getName());

            this.loglock.unlock();

            log.info("Joined {}", event.getChannel().getName());
            return;
        }

        this.mlog.populateChannel(event.getChannel());

        hist = this.mlog.getChannel(event.getChannel().getName());
        if (hist != null)
            hist.addUser(event.getUser().getNick());

        this.loglock.unlock();
        return;
    }

    @Override
    public void onPart(PartEvent event) {
        ChannelHist hist;

        this.loglock.lock();

        if (event.getUser().getNick().equalsIgnoreCase(event.getBot().getNick())) {
            // we parted the channel
            this.mlog.delChannel(event.getChannel().getName());

            this.loglock.unlock();

            log.info("Parted {}", event.getChannel().getName());
            return;
        }

        this.mlog.populateChannel(event.getChannel());

        // remove user from state
        hist = this.mlog.getChannel(event.getChannel().getName());
        if (hist != null)
            hist.delUser(event.getUser().getNick());

        this.loglock.unlock();
        return;
    }

    @Override
    public void onKick(KickEvent event) {
        ChannelHist hist;

        this.loglock.unlock();

        if (event.getRecipient().getNick().equalsIgnoreCase(event.getBot().getNick())) {
            // we were kicked from the channel
            this.mlog.delChannel(event.getChannel().getName());

            this.loglock.unlock();

            log.info("Kicked from {}", event.getChannel().getName());
            return;
        }

        this.mlog.populateChannel(event.getChannel());

        // remove user from state
        hist = this.mlog.getChannel(event.getChannel().getName());
        if (hist != null)
            hist.delUser(event.getRecipient().getNick());

        this.loglock.unlock();
        return;
    }

    @Override
    public void onQuit(QuitEvent event) {
        if (event.getUser().getNick().equalsIgnoreCase(event.getBot().getNick()))
            // ignore our own quit messages
            return;

        // delete user from channel message logs
        this.loglock.lock();
        this.mlog.deleteNick(event.getUser().getNick());
        this.loglock.unlock();

        return;
    }

    @Override
    public void onNickChange(NickChangeEvent event) {
        if (event.getOldNick().equalsIgnoreCase(event.getBot().getNick()) ||
            event.getNewNick().equalsIgnoreCase(event.getBot().getNick()))
            // ignore our own nick changes
            return;

        // change key under which user message history is stored
        this.loglock.lock();
        this.mlog.channelNick(event.getOldNick(), event.getNewNick());
        this.loglock.unlock();

        return;
    }

    @Override
    public void onListenerException(ListenerExceptionEvent event) {
        log.error("Listener exception! {}", event.getMessage());
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
                if (nick.equalsIgnoreCase(prefix)) {
                    // catch the special case where one user has a nick which is
                    // a substring of another's
                    ret = nick;
                    matches = 1;
                    break;
                } else if (nick.toLowerCase().startsWith(prefix.toLowerCase())) {
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
        private final String PRIVMSGFMT = "<%s%s> %s";
        private final String ACTIONFMT = "* %s%s %s";
        private LinkedList<UserMsg> history;
        private int nextId;

        UserHist() {
            this.history = new LinkedList<>();
            this.nextId = 0;
        }

        void pushMsg(String line, boolean ctcp) {
            this.pushMsg(new UserMsg(line, ctcp, this.nextId++));
        }

        void pushMsg(UserMsg msg) {
            this.history.addFirst(msg);

            if (this.history.size() > recall_limit)
                this.history.removeLast();
        }

        String searchReplace(String target, String search, String replace, int offset, boolean replaceAll) {
            String replacement, ret;
            UserMsg line, newline;
            PatternMatcher pm;
            StringBuilder stars;
            int id;

            line = null;

            pm = PatternMatcher.build(search);
            if (pm == null) {
                return null;
            }

            for (int i = 0; i < this.history.size(); i++) {
                if (pm.matches(this.history.get(i).line())) {
                    if (offset > 0) {
                        offset -= 1;
                    } else {
                        line = this.history.get(i);
                        break;
                    }
                }
            }

            if (line == null) return null;

            id = line.id();
            try {
                if (replaceAll) {
                    replacement = pm.replaceAll(line.line(), replace);
                } else {
                    replacement = pm.replaceFirst(line.line(), replace);
                }
            } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
                // invalid replacement, e.g. using $3 in a regex match which
                // has less than three capturing groups.
                return null;
            }
            stars = new StringBuilder();

            for (int i = 0; i < line.nextRevision(); i++)
                stars.append('*');

            ret = String.format(line.isctcp() ? this.ACTIONFMT : this.PRIVMSGFMT,
                                target, stars.toString(), replacement);

            newline = line.revise(replacement);

            for (UserMsg u: this.history)
                if (u.id() == id)
                    u.bumpRevision();

            this.pushMsg(newline);

            return ret;
        }

        String recall(String target, String search, int offset) {
            String recalled;
            UserMsg line;
            PatternMatcher pm;
            StringBuilder stars;

            line = null;

            pm = PatternMatcher.build(search);
            if (pm == null) {
                return null;
            }

            for (int i = 0; i < this.history.size(); i++) {
                if (pm.matches(this.history.get(i).line())) {
                    if (offset > 0) {
                        offset -= 1;
                    } else {
                        line = this.history.get(i);
                        break;
                    }
                }
            }

            if (line == null) return null;

            recalled = line.line();
            stars = new StringBuilder();

            for (int i = 0; i < line.revision(); i++)
                stars.append('*');

            return String.format(line.isctcp() ? this.ACTIONFMT : this.PRIVMSGFMT,
                                 target, stars.toString(), recalled);
        }
    }


    private class UserMsg {
        private final String line;
        private final boolean isctcp;
        private final int id;
        private final int revision;
        private int nextRevision;

        UserMsg(String line, boolean ctcp, int id) {
            this(line, ctcp, id, 0);
        }

        private UserMsg(String line, boolean ctcp, int id, int revision) {
            this.line = line;
            this.isctcp = ctcp;
            this.id = id;
            this.revision = revision;
            this.nextRevision = revision + 1;
        }

        boolean isctcp() {
            return this.isctcp;
        }

        String line() {
            return this.line;
        }

        int id() {
            return this.id;
        }

        int revision() {
            return this.revision;
        }

        int nextRevision() {
            return this.nextRevision;
        }

        void bumpRevision() {
            this.nextRevision += 1;
        }

        UserMsg revise(String replacement) {
            return new UserMsg(replacement, this.isctcp, this.id, this.nextRevision);
        }
    }
}

