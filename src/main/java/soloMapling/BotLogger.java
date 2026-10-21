package soloMapling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
Shift rightclick in NostalgiaStory direction
Copy below into powershell

powershell -Command "Get-Content -Path 'BotLog.txt' -Wait"

 */

/*
 * BotLog.txt in the server's working directory, written through log4j2 (logger "soloMapling.BotLog", appender
 * BotLogFile in log4j2.xml) so it rotates: it used to be a hand-rolled append that grew without bound (~18 MB/h,
 * nearly 1 GB on the test server). Same "[yyyy-MM-dd HH:mm:ss]: message" lines as before.
 */
public class BotLogger {
    static final boolean log = true;
    private static final Logger LOG = LoggerFactory.getLogger("soloMapling.BotLog");

    public static void log(String message) {
        if (!log) {
            return;
        }
        LOG.info(message);
    }
}
