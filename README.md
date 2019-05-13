# timemachine

`timemachine` is an IRC bot, which processes inline sed-like search-and-replace
strings (`s///`) and an inline history recall command (`p//`).

## Building

`timemachine` is written in Java 8, and uses the
[PircBotX](https://github.com/pircbotx/pircbotx) IRC library. Maven is used for
building; issue

```
mvn compile package
```

to create a combined JAR file including all dependency libraries in the
`target/` directory.

## Running

### Startup

Run the generated JAR file with the `-h` flag to obtain a definitive list of
flags. The IRC server hostname and port, the bot's nick, ircname, and realname,
and the message history size limit must all be provided. Passworded servers and
NickServ authentication are supported by passing the required password in an
environment variable, the name of which is passed on the command line;
`timemachine` will read the respective password and then erase it from the
environment. Bot owners are specified by providing a series regular expressions
which will match their hostmasks.

### User interface

The search and replace function is triggered when messages of the following
format are sent to a channel:

```
s/regex/replacement/[target][~offset]
```

`target` and `offset` may be omitted, in which case they are set to the user who
sent the message and zero, respectively. If `target` is specified explicitly,
then `timemachine` will attempt to find a unique user in the channel whose nick
starts with `target` (i.e. it performs a prefix-based search for the intended
target).

`timemachine` will search backwards through the channel's recorded message
history for messages from `target` matching `regex`. `offset` number of matches
will be skipped, after which the message with the text matched by `regex` will
have the matching substring replaced by `replacement`. Note that the modified
message is stored, and any future search-and-replace operations will be
performed on the most recent edit of a message.

The recall function is similarly triggered when a message of the following
format is sent to a channel:

```
p/regex/[target][~offset]
```

`target` and `offset` have the same meaning as for search-and-replace. This will
search backwards through the channel's recorded message history for the target
user for messages which match `regex`, skip `target` matches and then recall the
next matching line in the recorded history.

### Owner interface

`timemachine` may be controlled via private message by any user whose hostmask
matches one of the specified owner regular expressions. The following commands
are understood:

- `join <chan>`: join the `chan` channel.

- `part <chan>`: leave the `chan` channel.

- `ignore <nick>`: add `nick` to the ignore list.

- `unignore <nick>`: remove `nick` from the ignore list.

- `quit`: quit and disconnect from the server.

## License

Licensed under the ISC license; see `LICENSE` file for details.
