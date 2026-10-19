package constants.net;

import java.awt.*;

public class ServerConstants {

    //Server Version
    public static final short VERSION = 83;

    public static final String REGISTRATION_URL = System.getenv().getOrDefault("SLUMSMS_REGISTRATION_WEBHOOK", "");
    public static final Color EMBED_COLOR = Color.decode("#e53349");

    //Debug Variables
    public static int[] DEBUG_VALUES = new int[10];             // Field designed for packet testing purposes
}
