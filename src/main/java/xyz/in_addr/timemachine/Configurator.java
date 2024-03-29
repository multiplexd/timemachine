/* See LICENSE file for license details */

package xyz.in_addr.timemachine;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.net.ssl.SSLSocketFactory;

import xyz.in_addr.timemachine.GetOpt;

import org.pircbotx.Configuration;
import org.pircbotx.UtilSSLSocketFactory;
import org.pircbotx.delay.BinaryBackoffDelay;

/**
 * Bot configuration handler.
 */
public class Configurator {
    public static class TMConfig {
        public Configuration.Builder config;
        public int recalllimit;
        public List<Pattern> ownerlist;
        public Set<String> ignorelist;
        public String initialmodes, logchannel;

        TMConfig(Configuration.Builder builder, int limit, Set<String> ignores,
                 List<Pattern> owners, String modes, String logchannel) {
            this.config = builder;
            this.recalllimit = limit;
            this.ignorelist = ignores;
            this.ownerlist = owners;
            this.initialmodes = modes;
            this.logchannel = logchannel;
        }
    }

    private static Configuration.Builder configBuilderDefaults() {
        return new Configuration.Builder()
            .setAutoNickChange(true)
            .setAutoReconnect(true)
            .setAutoReconnectDelay(new BinaryBackoffDelay(10000, 300000))
            .setSocketTimeout(120000);
    }

    private static int getInt(String s) {
        int ret = 0;

        try {
            ret = Integer.parseUnsignedInt(s);
        } catch (NumberFormatException nfe) {
            System.err.printf("invalid unsigned integer: '%s'\n", s);
            System.exit(1);
        }

        return ret;
    }

    private static void exitIf(boolean b, String msg) {
        if (b) {
            System.err.println(msg);
            System.exit(1);
        }

        return;
    }

    public static TMConfig loadConfig(String[] args) {
        Configuration.Builder builder;
        String host, nick, realname, sourcehost, ircname, nickserv, spass,
            env, modes, logchannel;
        String[] split;
        int port, recall, opt, ret;
        boolean ssl, sslnoverify;
        List<String> autojoin;
        List<Pattern> owners;
        Set<String> ignores;
        GetOpt options;
        InetAddress saddr;
        Pattern pat;

        host = null; port = 0; ssl = false; sslnoverify = false; sourcehost = null;
        recall = 0; nick = null; realname = null; ircname = null; modes = null;
        nickserv = null; spass = null; saddr = null; pat = null; logchannel = null;
        /* needed for thread safety */
        ignores = Collections.synchronizedSet(new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
        owners = new ArrayList<>();
        autojoin = new ArrayList<>();

        options = new GetOpt(args, ":hH:p:sQS:n:i:r:N:k:m:l:L:I:O:A:", false);

        while ((opt = options.getOpt()) != -1) {
            switch (opt) {
            case 'h':
                printUsage();
                break;
            case 'H':
                host = options.optarg();
                break;
            case 'p':
                port = getInt(options.optarg());
                break;
            case 's':
                ssl = true;
                break;
            case 'Q':
                sslnoverify = true;
                break;
            case 'S':
                sourcehost = options.optarg();
                break;
            case 'n':
                nick = options.optarg();
                break;
            case 'i':
                ircname = options.optarg();
                break;
            case 'r':
                realname = options.optarg();
                break;
            case 'N':
                nickserv = options.optarg();
                break;
            case 'k':
                spass = options.optarg();
                break;
            case 'm':
                modes = options.optarg();
                break;
            case 'l':
                recall = getInt(options.optarg());
                break;
            case 'L':
                logchannel = options.optarg();
                break;
            case 'I':
                ignores.add(options.optarg());
                break;
            case 'O':
                try {
                    pat = Pattern.compile(options.optarg());
                } catch (PatternSyntaxException pse) {
                    System.err.printf("bad owner hostmask regular expression: '%s'\n", options.optarg());
                    System.exit(1);
                }
                owners.add(pat);
                break;
            case 'A':
                autojoin.add(options.optarg());
                break;
            case ':':
                System.err.printf("expected argument to option: -%c\n", options.optopt());
                System.exit(1);
                break;
            case '?':
            default:
                System.err.printf("unrecognised option: -%c\n", options.optopt());
                System.exit(1);
                break;
            }
        }

        if (options.optind() < args.length) {
            System.err.println("unexpected trailing arguments");
            System.exit(1);
        }

        exitIf(host == null, "missing irc server hostname");
        exitIf(port == 0, "missing irc port number");
        exitIf(nick == null, "missing bot nick");
        exitIf(ircname == null, "missing bot ircname");
        exitIf(realname == null, "missing bot realname");
        exitIf(recall == 0, "missing message history limit");
        exitIf(!ssl && sslnoverify, "cannot specify -Q without -s");

        builder = configBuilderDefaults();
        builder.addServer(host, port)
            .setName(nick)
            .setLogin(ircname)
            .setRealName(realname);

        if (ssl) {
            if (sslnoverify)
                builder.setSocketFactory(new UtilSSLSocketFactory().trustAllCertificates());
            else
                builder.setSocketFactory(SSLSocketFactory.getDefault());
        }

        if (sourcehost != null) {
            try {
                saddr = InetAddress.getByName(sourcehost);
            } catch (UnknownHostException uhe) {
                System.err.println("source host not found:" + sourcehost);
                System.exit(1);
            }

            builder.setLocalAddress(saddr);
        }

        if (nickserv != null) {
            env = System.getenv(nickserv);
            exitIf(env == null, "could not find environment variable " + nickserv);
            builder.setNickservPassword(env).setNickservDelayJoin(true);
        }

        if (spass != null) {
            env = System.getenv(spass);
            exitIf(env == null, "could not find environment variable " + spass);
            builder.setServerPassword(env);
        }

        for (String channel: autojoin) {
            if (channel.contains(":")) {
                split = channel.split(":");
                exitIf(split.length != 2, "bad channel definition: " + channel);
                builder.addAutoJoinChannel(split[0], split[1]);
            } else {
                builder.addAutoJoinChannel(channel);
            }
        }

        return new TMConfig(builder, recall, ignores, owners, modes, logchannel);
    }

    private static void printUsage() {
        String usage = "Usage: java -jar timemachine.jar <flags>\n\n" +
            "        -h        Display this help\n" +
            "        -H host   IRC server host\n" +
            "        -p port   IRC server port\n" +
            "        -s        Use TLS\n" +
            "        -Q        Do not verify server TLS certificates\n\n" +
            "        -S addr   Source address for outgoing connection\n" +
            "        -n nick   Bot nick\n" +
            "        -i name   Bot ircname\n" +
            "        -r name   Bot realname\n\n" +
            "        -N env    Environment variable containing NickServ password\n" +
            "        -k env    Environment variable containing server password\n" +
            "        -m modes  Mode string to set upon connect\n\n" +
            "        -l hist   Number of lines of history to record\n" +
            "        -I nick   Add nick to ignore list (may be specified more than once)\n" +
            "        -O regex  Add hostmask regex to owner list (may be specified more than once)\n" +
            "        -A chan   Add channel to autojoin list (may be specified more than once;\n" +
            "                  channel key may be provided by separating channel and key with colon,\n" +
            "                  e.g. #foo:key)\n" +
            "        -L chan   Set channel where log messages will be sent, if channel is joined.\n";

        System.out.print(usage);
        System.exit(0);
    }
}

