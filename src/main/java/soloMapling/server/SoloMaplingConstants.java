package soloMapling.server;

import net.server.Server;
import net.server.channel.Channel;

public class SoloMaplingConstants {

    /** Channel 1 of the home world, where the bots live. Null before the worlds start. */
    public static Channel mainChannel() {
        return Server.getInstance().getChannel(GameConstants.homeWorld(), GameConstants.CHANNEL_1);
    }

    public static class GameConstants {
        /**
         * The world bots live in: the server's home world, the first world config.yaml starts (ticket 41; it was
         * hard-wired to Scania, 0). 0 before the worlds start.
         */
        public static int homeWorld() {
            return Server.getInstance().getHomeWorldId();
        }

        public static final int CHANNEL_1 = 1;
        public static final int BOT_BASE_ID = 1000000000;
        public static final int CONSOLE_BOT_ID = 999999999;
        public static final int MERCHANT_BASE_ID = 1100000000;
    }

}
