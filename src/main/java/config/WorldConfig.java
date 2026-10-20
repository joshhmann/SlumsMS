package config;

public class WorldConfig {
    /** World id (0 = Scania, 1 = Bera, ...); -1 = the entry's position in the list. See {@link WorldConfigs}. */
    public int id = -1;
    public int flag = 0;
    public String server_message = "Welcome!";
    public String event_message = "";
    public String why_am_i_recommended = "";
    public int channels = 1;
    public int exp_rate = 1;
    public int meso_rate = 1;
    public int drop_rate = 1;
    public int boss_drop_rate = 1;
    public int quest_rate = 1;
    public int travel_rate = 1;
    public int fishing_rate = 1;
}
