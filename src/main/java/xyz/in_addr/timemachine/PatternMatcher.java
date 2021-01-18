/* See LICENSE file for license details */

package xyz.in_addr.timemachine;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

/**
 * Encapsulation class over Google re2j to handle namespace collisions with
 * standard library regex functions.
 */
public class PatternMatcher {
    private final Pattern regex;

    private PatternMatcher(Pattern p) {
        this.regex = p;
    }

    public static PatternMatcher build(String pattern) {
        Pattern pat;

        try {
            pat = Pattern.compile(pattern);
        } catch (PatternSyntaxException pse) {
            return null;
        }

        return new PatternMatcher(pat);
    }

    public boolean matches(String query) {
        return this.regex.matcher(query).find();
    }

    public String replaceFirst(String query, String replacement) {
        return this.regex.matcher(query).replaceFirst(replacement);
    }

    public String replaceAll(String query, String replacement) {
        return this.regex.matcher(query).replaceAll(replacement);
    }
}
