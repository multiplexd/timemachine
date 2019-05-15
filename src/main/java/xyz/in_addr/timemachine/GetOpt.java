/* See LICENSE file for license details */

package xyz.in_addr.timemachine;

import java.lang.IllegalArgumentException;

/**
 * Pure Java reimplementation of the Unix standard library function
 * getopt(3). Based on the musl libc implementation.
 *
 * The implemented behaviour is only that mandated by the POSIX standard, plus
 * the GNU extension which permits "-oarg" as a synonym for "-o arg".
 */

public final class GetOpt {
    // Object internal state
    private final String optstring;
    private String args[];

    // Getopt algorithm state
    private int optind = 0; // Next element of the args array to be parsed
    private char optopt; // Option character which caused an error
    private boolean opterr; // Whether we print our own error messages
    private int optpos = 0; // Next character in the String to be parsed
    private String optarg = null; // Argument to an option

    /**
     * Create a GetOpt options parser object
     *
     * This object provides similar functionality to the Unix getopt(3) C
     * library function for parsing command line arguments. The options to be
     * parsed are passed as a string array and the option characters that are
     * recognised are passed as a String. Each option character may be followed
     * by a colon character to indicate that it takes an argument. If the first
     * character in the option string is itself a colon then the getOpt() method
     * below will return a colon instead of a question mark when an expected
     * option is not present.
     *     
     * @param args The String array of arguments to be parsed (such as those
     * passed to main()).
     * @param optstring An String containing a list of character options.
     * @param printmsg Whether this GetOpt object should print its own error messages.
     * @throws IllegalArgumentException Thrown if the option string is not valid.
     */
    public GetOpt(String args[], String optstring, boolean printmsg) throws IllegalArgumentException {
        // No deep clone is performed on these arguments; changing them externally is UNDEFINED
        // BEHAVIOUR.

        if (optstring.length() == 0 || (optstring.length() == 1 && optstring.charAt(0) == ':')) {
            throw new IllegalArgumentException();
        }
        
        this.optstring = optstring;
        this.args = args;
        this.opterr = printmsg;
    }

    // Getters for internal state which is useful to external contexts
    public int optind() {
        return this.optind;
    }

    public String optarg() {
        return this.optarg;
    }

    public char optopt() {
        return this.optopt;
    }

    /**
     * Get a command line option character.
     *
     * This function returns the next option character found in the list of
     * arguments; if the argument found takes an option, then the private field
     * optopt will be set to the value of the argument. If the method reaches
     * the end of the options to be parsed, either because one of the arguments
     * is '--' or it has reached the end of the String array, or it cannot parse
     * an argument, -1 is returned. If the provided options are invalid, i.e. an
     * unrecognised option is found or a required argument is missing then a
     * question mark or (optionally) a colon is returned and the private field
     * optopt is set to the character which caused the error.
     *
     * @return The next option character, or, on error, -1, a question mark or
     * (optionally) a colon.
     */
    public int getOpt() {
        char optchar;
        boolean found = false;
        int i;

        this.optarg = null; // Reset in case the next option we parse has an argument

        // Check for further options in argument vector
        
        if (optind >= this.args.length) {
            return -1;
        } else if (this.args[optind].length() == 0) {
            return -1;
        } else if (this.args[optind].charAt(0) != '-') {
            return -1;
        } else if (this.args[optind].length() == 1) {
            return -1;
        } else if (this.args[optind].charAt(1) == '-') {
            optind++;
            return -1;
        }
        if (optpos == 0) optpos++; // Start from the second char in the String
        optchar = this.args[optind].charAt(optpos);
        optpos++;

        // Check for option at end of argument string
        if (this.args[optind].length() == optpos) {
            optpos = 0;
            optind++;
        }

        // Now parse the options.
        for (i = 0; i < this.optstring.length(); i++) {
            if (optchar == this.optstring.charAt(i)) {
                found = true;
                break;
            }
        }

        if (!found || optchar == ':') { // Invalid option
            optopt = optchar;
            if (optstring.charAt(0) != ':' && opterr) {
                System.err.printf("Unrecognised option: '%c'\n", optchar);
            }
            return '?';
        }

        // Check whether the option takes an argument
        if ((i < this.optstring.length() - 1) && (this.optstring.charAt(i + 1) == ':')) {
            if (optind >= this.args.length) {
                // Argument expected, but parsing has reached the end of command line arguments
                optopt = optchar;
                if (optstring.charAt(0) == ':') {
                    return ':';
                }

                if (opterr) {
                    System.err.printf("Option requires an argument: '%c'\n", optchar);
                }
                
                return '?';
            }

            // Copy the argument to our option into optarg.
            optarg = this.args[optind].substring(optpos, this.args[optind].length());
            optind++;
            optpos = 0;
        }

        return optchar;
    }
}
