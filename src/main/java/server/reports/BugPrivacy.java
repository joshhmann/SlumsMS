package server.reports;

import java.util.regex.Pattern;

/**
 * Ticket 131: what never goes into a bug report's context. Server log lines are dropped whole when they look like
 * they carry someone's secrets or address (passwords, PIN/PIC, IPs, e-mails, hashes, session data, login names,
 * whispers); uploaded client logs are redacted line by line (Windows user paths, PC/user names, IPs, e-mails) and lines
 * about passwords are dropped. Patterns are deliberately broad: a lost log line costs little, a leaked one a lot.
 */
public final class BugPrivacy {
    private BugPrivacy() {
    }

    /** A server log line containing any of these is never copied into a report. */
    static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(pass(word|wd)?\\b|\\bpwd\\b|\\bpin\\b|\\bpic\\b|secret|\\btokens?\\b|\\bsalt\\b|\\bhash\\b|[_ ]hash\\b|"
                    + "e-?mail|whisper|\\bip\\b|\\baddress\\b|\\bremote ?(addr|address|host|ip)\\b|session ?(id|key)|"
                    + "cookie|authori[sz]ation|api[_ -]?key|"
                    + "\\baccount\\s+(name\\b|[A-Za-z_])|accountname|\\blogin\\s+[A-Za-z_]|"
                    + "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b|"                      // IPv4
                    + "\\b(?:[0-9a-f]{1,4}:){3,7}[0-9a-f]{1,4}\\b|"            // IPv6 (enough of it)
                    + "[\\w.+-]+@[\\w-]+\\.[\\w.-]+|"                          // e-mail
                    + "\\b[0-9a-f]{32,}\\b)");                                // hashes, keys

    /** True when a server log line may go into a report. */
    public static boolean serverLineAllowed(String line) {
        return line != null && !SENSITIVE.matcher(line).find();
    }

    private static final Pattern DROP_CLIENT_LINE = Pattern.compile("(?i)(pass(word|wd)?\\b|\\bpwd\\b|\\bpin\\b|\\bpic\\b|"
            + "secret|token|cookie|authori[sz]ation|api[_ -]?key|serial|license key)");
    private static final Pattern USER_PATH = Pattern.compile("(?i)([A-Z]:[\\\\/]+(?:Users|Documents and Settings)[\\\\/]+)[^\\\\/\\r\\n\"']+");
    private static final Pattern NAMED = Pattern.compile("(?i)\\b(computer ?name|pc ?name|host ?name|user ?name|user ?domain|"
            + "logon ?server|machine ?name|account ?name|login)\\s*[=:]\\s*\\S+");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\b");
    private static final Pattern IPV6 = Pattern.compile("(?i)\\b(?:[0-9a-f]{1,4}:){3,7}[0-9a-f]{1,4}\\b");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern MAC = Pattern.compile("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b");

    /**
     * The server's second scrub of an uploaded client log (the client scrubs first): drops lines about passwords and
     * keys, redacts user paths, PC and user names, IP and MAC addresses and e-mails. The slums.dll hash stays (it is
     * a file hash, not a person's). Control characters other than newline and tab are removed.
     */
    public static String scrubClientText(String text, String accountName) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\r?\n", -1)) {
            if (DROP_CLIENT_LINE.matcher(line).find()) {
                out.append("[line removed]\n");
                continue;
            }
            String l = USER_PATH.matcher(line).replaceAll("$1<user>");
            l = NAMED.matcher(l).replaceAll("$1=<redacted>");
            l = EMAIL.matcher(l).replaceAll("<email>");
            l = MAC.matcher(l).replaceAll("<mac>");
            l = IPV6.matcher(l).replaceAll("<ip>");
            l = IPV4.matcher(l).replaceAll("<ip>");
            if (accountName != null && accountName.length() >= 3) {
                l = l.replaceAll("(?i)" + Pattern.quote(accountName), "<account>");
            }
            out.append(l.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]", "")).append('\n');
        }
        out.setLength(Math.max(0, out.length() - 1));
        return out.toString();
    }
}
