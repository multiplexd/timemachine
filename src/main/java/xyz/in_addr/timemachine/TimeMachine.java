/* See LICENSE file for license details */

package xyz.in_addr.timemachine;

import java.util.Collections;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pircbotx.Channel;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ActionEvent;
import org.pircbotx.hooks.events.ListenerExceptionEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.types.GenericChannelUserEvent;
import org.pircbotx.hooks.types.GenericMessageEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main bot event handler.
 */
public class TimeMachine extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(TimeMachine.class);

    // substitution and recall commands. the regexen here are based on ones
    // originally shared by puck meerburg, which were subsequently hacked on for
    // timemachine. i spent an inordinate amount of time on debacktrackifying
    // them for compatibility with google's re2j library, however puck still
    // writes better regexes than i do, so these are based on more recent
    // versions of her regexes which support arbitrary input delimiters. the
    // code which *really* needs re2j's linear-time matching now indirects
    // through a wrapper class to prevent naming collisions, and we default to
    // the standard library's backtracking implementation otherwise.
    //
    // fun fact: matching regex with regex is hard.
    private final Pattern SED_MATCH = Pattern.compile("^[sS](\\W)((?:\\\\\\1|(?:(?!\\1).))*)(?!\\\\)\\1((?:\\\\\\1|\\\\\\\\|(?:(?!\\1).))*)(?:\\1([^ ~]*)((?:~[0-9]+)?))?");
    private final Pattern PRINT_MATCH = Pattern.compile("^[pP](\\W)((?:\\\\\\1|(?:(?!\\1).))*)(?!\\\\)\\1([^ ~]*)((?:~[0-9]+)?)");

    private final Pattern ADDRESSED_MATCH = Pattern.compile("^\\s*([^,:;\\s/]+)[,:;]\\s+");
    private final String SOURCE_URL = "https://github.com/multiplexd/timemachine"; // self documentation

    private final Pattern BOTSNACK_MATCH = Pattern.compile("^\\s*botsnack\\s*$");
    private final String BOTSNACK_RESPONSE = ":D";

    private final Set<String> ignoreList;
    private final Map<String, MessageLog> messageLog;
    private final String logChannel;
    private final int recallLimit;

    // ignores is expected to be a Set implementation which is safe against
    // concurrent accesses.
    public TimeMachine(int limit, Set<String> ignores, String logchan) {
        this.recallLimit = limit;
        this.ignoreList = ignores;
        this.logChannel = logchan;
        this.messageLog = Collections.synchronizedMap(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));

        log.info("Time machine is initialised. Vworp vworp!");
    }

    // must be called with messageLog's lock held!
    private MessageLog getChannelLog(String channel) {
        MessageLog ret;

        ret = this.messageLog.get(channel);

        if (ret == null) {
            ret = new MessageLog();
            this.messageLog.put(channel, ret);
        }

        return ret;
    }

    private class Message {
        private final String user, message;
        private final boolean ctcp;

        private String addressee;
        private int addresseeOffset; // offset where the body of an addressed message begins.
        private int matchableOffset; // offset where the matchable segment of the message starts.

        Message(String user, String message, boolean ctcp) {
            this.user = user;
            this.message = message;
            this.ctcp = ctcp;
        }

        // original message, verbatim
        String getMessage() {
            return this.message;
        }

        String getUser() {
            return this.user;
        }

        boolean isCtcp() {
            return this.ctcp;
        }

        // set addressee prefix, and offset for message body.
        // e.g. for message "john: howdy pal!", addressee is set to "john", and
        // offset is set to 6.
        void setAddressee(String addressee, int offset) {
            this.addressee = addressee;
            this.addresseeOffset = offset;
        }

        String getAddressee() {
            return this.addressee;
        }

        String getAddressedMessage() {
            return this.message.substring(this.addresseeOffset);
        }

        // set offset for a message's matchable portion, distinct from the
        // non-matchable prefix. note that this is relative to the addressed
        // message offset.
        void setPrefixOffset(int offset) {
            this.matchableOffset = offset;
        }

        String getMessagePrefix() {
            if (this.matchableOffset == 0) {
                return "";
            } else {
                return this.message.substring(0, this.addresseeOffset + this.matchableOffset);
            }
        }

        String getMessageBody() {
            if (this.matchableOffset == 0) {
                return this.message;
            } else {
                return this.message.substring(this.addresseeOffset + this.matchableOffset);
            }
        }
    }

    @Override
    public void onListenerException(ListenerExceptionEvent event) {
        String msg;

        msg = String.format("Listener exception! Event class: %s; exception: %s",
            event.getSourceEvent().getClass().getName(),
            event.getException().toString());

        log.error("{}", msg);
        if (this.logChannel != null) {
            event.getBot().sendIRC().message(this.logChannel, msg);
        }
    }

    @Override
    public void onMessage(MessageEvent event) {
        this.messageDriver(event, false);
    }

    @Override
    public void onAction(ActionEvent event) {
        this.messageDriver(event, true);
    }

    private <T extends GenericMessageEvent & GenericChannelUserEvent> void messageDriver(T event, boolean isctcp) {
        Message msg;
        Supplier<String> result;
        String reply;
        Matcher botsnack, addressed;
        MessageLog history;

        reply = null;
        msg = new Message(event.getUser().getNick(), event.getMessage(), isctcp);

        if (this.ignoreList.contains(msg.getUser())) {
            return;
        }

        result = tryBotsnack(msg);

        if (result == null) {
            checkAddressee(msg);
            result = tryDocsRequest(msg, event.getBot().getNick());
        }

        synchronized (this.messageLog) {
            history = this.getChannelLog(event.getChannel().getName());

            if (result == null) {
                result = tryRecall(history, msg);
            }

            if (result == null) {
                result = trySearchReplace(history, msg);
            }

            history.pushMsg(msg);

            if (result != null) {
                reply = result.get();
            }
        }

        if (reply != null) {
            event.getChannel().send().message(reply);
        }
    }

    private Supplier<String> tryBotsnack(Message msg) {
        if (!msg.isCtcp() && this.BOTSNACK_MATCH.matcher(msg.getMessage()).matches()) {
            log.info("Sending botsnack response");
            return () -> this.BOTSNACK_RESPONSE;
        }

        return null;
    }

    private void checkAddressee(Message msg) {
        Matcher matcher;

        matcher = this.ADDRESSED_MATCH.matcher(msg.getMessage());

        if (matcher.find()) {
            msg.setAddressee(matcher.group(1), matcher.end());
        }
    }

    private Supplier<String> tryDocsRequest(Message msg, String nick) {
        if (msg.isCtcp() || msg.getAddressee() == null) {
            return null;
        }

        if (msg.getAddressee().equalsIgnoreCase(nick) &&
                (msg.getAddressedMessage().equalsIgnoreCase("docs") ||
                msg.getAddressedMessage().equalsIgnoreCase("source"))) {
            log.info("Sending source and docs URLs");
            return () -> this.SOURCE_URL;
        }

        return null;
    }

    private static Supplier<String> empty() {
        return () -> null;
    }

    // TODO(multi): p/foo/g for global recall
    // TODO(multi): p[+-][0-9]+
    private Supplier<String> tryRecall(MessageLog history, Message msg) {
        Matcher match;
        String delim, query, target, offstring;
        boolean exactTarget;
        int skipMatches;

        match = this.PRINT_MATCH.matcher(msg.getAddressedMessage());
        if (!match.find()) return null;

        delim = match.group(1);

        // guard against invalid delimiters
        if (delim.equals("\\") || delim.equals(" ")) {
            return null;
        }

        log.info("Recall command triggered");

        query = match.group(2).replace("\\" + delim, delim);
        target = match.group(3);
        offstring = match.group(4);

        msg.setPrefixOffset(match.end());

        exactTarget = false;
        if (target.equals("")) {
            exactTarget = true;
            target = msg.getAddressee() != null ? msg.getAddressee() : msg.getUser();
        }

        if (offstring.equals("")) {
            skipMatches = 0;
        } else {
            try {
                // remove leading tilde
                skipMatches = Integer.parseUnsignedInt(offstring.substring(1));
            } catch (NumberFormatException nfe) {
                return empty();
            }
        }

        return history.recall(target, exactTarget, query, skipMatches);
    }

    private Supplier<String> trySearchReplace(MessageLog history, Message msg) {
        Matcher match;
        String delim, query, replacement, target, offstring;
        boolean exactTarget, global;
        int skipMatches;

        global = false;

        match = this.SED_MATCH.matcher(msg.getAddressedMessage());
        if (!match.find()) return null;

        delim = match.group(1);

        // guard against invalid delimiters
        if (delim.equals("\\") || delim.equals(" ")) {
            return null;
        }

        log.info("Search and replace command triggered");


        query = match.group(2).replace("\\" + delim, delim);
        replacement = match.group(3).replace("\\" + delim, delim);
        target = match.group(4);
        offstring = match.group(5);

        if (target == null && offstring == null && replacement.equals("")) {
            // reject s/foo/ form, with an empty replacement string
            // TODO(multi): require trailing slash?
            return null;
        }

        msg.setPrefixOffset(match.end());

        exactTarget = false;
        if (target == null || target.equals("") || target.equals("g")) {
            exactTarget = true;
            global = target == null ? false : target.equals("g");
            target = msg.getAddressee() != null ? msg.getAddressee() : msg.getUser();
        }

        if (offstring == null || offstring.equals("")) {
            skipMatches = 0;
        } else {
            try {
                // remove leading tilde
                skipMatches = Integer.parseUnsignedInt(offstring.substring(1));
            } catch (NumberFormatException nfe) {
                return empty();
            }
        }

        return history.searchReplace(target, exactTarget, query, replacement, skipMatches, global);
    }


    // logging structure for messages in a single channel.
    private class MessageLog {
        private final String PRIVMSGFMT = "<%s%s> %s";
        private final String ACTIONFMT = "* %s%s %s";

        private final LinkedList<LogEntry> messages;
        private int nextId;

        MessageLog() {
            this.messages = new LinkedList<>();
            this.nextId = 0;
        }

        void pushMsg(Message msg) {
            this.pushMsg(
                new LogEntry(
                    this.nextId++,
                    msg.getUser(),
                    msg.getMessagePrefix(),
                    msg.getMessageBody(),
                    msg.isCtcp()
                )
            );
        }

        private void pushMsg(LogEntry msg) {
            // messages are stored in the linked list in reverse order -- new
            // messages are prepended to the list, and old messages are dropped
            // from the end of the list.
            this.messages.addFirst(msg);

            if (this.messages.size() > TimeMachine.this.recallLimit) {
                this.messages.removeLast();
            }
        }

        private boolean targetMatches(String nick, String target, boolean exactMatch) {
            if (exactMatch) {
                return nick.equalsIgnoreCase(target);
            } else {
                return nick.toLowerCase().startsWith(target.toLowerCase());
            }
        }


        Supplier<String> searchReplace(String target, boolean exactTarget, String searchRegex, String replacement, long skipMatches, boolean replaceAll) {
            PatternMatcher pm;
            ListIterator<LogEntry> iter;
            LogEntry line, newline, tmp;
            String replacedMessage, ret;
            StringBuilder stars;
            int id;

            line = null;

            pm = PatternMatcher.build(searchRegex);
            if (pm == null) {
                return TimeMachine.empty();
            }

            iter = this.messages.listIterator(0);
            while (iter.hasNext()) {
                tmp = iter.next();

                if (!this.targetMatches(tmp.nick(), target, exactTarget)) {
                    continue;
                }

                if (tmp.body() == null) {
                    continue;
                }

                if (!pm.matches(tmp.body())) {
                    continue;
                }

                if (skipMatches > 0) {
                    skipMatches -= 1;
                } else {
                    line = tmp;
                    break;
                }
            }

            if (line == null) return TimeMachine.empty();

            id = line.id();
            try {
                if (replaceAll) {
                    replacedMessage = pm.replaceAll(line.body(), replacement);
                } else {
                    replacedMessage = pm.replaceFirst(line.body(), replacement);
                }
            } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
                // invalid replacement, e.g. using $3 in a regex replacement
                // which has less than three capturing groups.
                return TimeMachine.empty();
            }

            newline = line.revise(replacedMessage);

            iter = this.messages.listIterator(0);
            while (iter.hasNext()) {
                tmp = iter.next();
                if (tmp.id() == id) {
                    tmp.notifyRevised();
                }
            }

            stars = new StringBuilder();
            for (int i = 0; i < newline.revision(); i++) {
                stars.append('*');
            }

            log.info("Search and replace command matched, returning result");

            ret = String.format(newline.isctcp() ? this.ACTIONFMT : this.PRIVMSGFMT,
                                line.nick(), stars.toString(), newline.fullMessage());

            return () -> {
                this.pushMsg(newline);
                return ret;
            };
        }

        // TODO(multi): target == null indicates searching all messages?
        // TODO(multi); p[+-][0-9]+ syntax
        Supplier<String> recall(String target, boolean exactTarget, String searchRegex, int skipMatches) {
            PatternMatcher pm;
            ListIterator<LogEntry> iter;
            LogEntry line, tmp;
            StringBuilder stars;
            final String ret;

            line = null;

            pm = PatternMatcher.build(searchRegex);
            if (pm == null) {
                return TimeMachine.empty();
            }

            iter = this.messages.listIterator(0);
            while (iter.hasNext()) {
                tmp = iter.next();

                if (!this.targetMatches(tmp.nick(), target, exactTarget)) {
                    continue;
                }

                if (tmp.body().length() == 0) {
                    continue;
                }

                if (!pm.matches(tmp.body())) {
                    continue;
                }

                if (skipMatches > 0) {
                    skipMatches--;
                } else {
                    line = tmp;
                    break;
                }
            }

            if (line == null) return TimeMachine.empty();

            stars = new StringBuilder();
            for (int i = 0; i < line.revision(); i++) {
                stars.append('*');
            }

            log.info("Recall command matched, returning result");

            ret = String.format(line.isctcp() ? this.ACTIONFMT : this.PRIVMSGFMT,
                                line.nick(), stars.toString(), line.fullMessage());

            return () -> ret;
        }
    }

    private class LogEntry {
        private final int id, revision;
        private final String nick, prefix, body;
        private final boolean ctcp;
        private int nextRevision;

        private LogEntry(int id, int revision, String nick, String prefix, String body, boolean ctcp) {
            if (nick == null || prefix == null || body == null) {
                throw new NullPointerException("String parameter is unexpectedly null");
            }

            if (prefix.length() == 0 && body.length() == 0) {
                throw new IllegalArgumentException("Message prefix and message body must not both be empty");
            } else if (prefix.length() != 0 && ctcp) {
                throw new IllegalArgumentException("CTCP messages may not have a non-empty prefix part");
            }

            this.id = id;
            this.revision = revision;
            this.nick = nick;
            this.prefix = prefix;
            this.body = body;
            this.ctcp = ctcp;
            this.nextRevision = revision + 1;
        }

        LogEntry(int id, String nick, String prefix, String body, boolean ctcp) {
            this(id, 0, nick, prefix, body, ctcp);
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

        void notifyRevised() {
            this.nextRevision++;
        }

        boolean isctcp() {
            return this.ctcp;
        }

        String nick() {
            return this.nick;
        }

        String prefix() {
            return this.prefix;
        }

        String body() {
            return this.body;
        }

        String fullMessage() {
            return this.prefix + this.body;
        }

        LogEntry revise(String newMessage) {
            if (this.body.length() == 0) {
                throw new IllegalArgumentException("cannot revise channel message without a message body");
            }

            return new LogEntry(this.id, this.nextRevision, this.nick, this.prefix, newMessage, this.ctcp);
        }
    }
}
