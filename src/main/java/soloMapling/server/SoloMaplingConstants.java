package soloMapling.server;

import net.server.Server;
import net.server.channel.Channel;

public class SoloMaplingConstants {

    public static final Channel mainChannel = Server.getInstance().getChannel(0, 1);

    public static class GameConstants {
        public static final int WORLD_SCANIA = 0;
        public static final int CHANNEL_1 = 1;
        public static final int BOT_BASE_ID = 1000000000;
        public static final int CONSOLE_BOT_ID = 999999999;
        public static final int MERCHANT_BASE_ID = 1100000000;
    }

}
