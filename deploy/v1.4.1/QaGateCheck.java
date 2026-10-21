// 1.4.1 rehearsal: is the !qa suite (and the attack-capture commands) registered under this JVM's -D flags?
// Run against the staged jar with live's unit flags: java <live -D flags> -cp <dir>:Cosmic.jar QaGateCheck
// Prints one line: qa=<bool> attackcapture=<bool> commands qa/classtest/botshowcase=<bool>/<bool>/<bool>
import java.lang.reflect.Method;

public class QaGateCheck {
    public static void main(String[] a) throws Exception {
        Object ex = client.command.CommandsExecutor.getInstance();
        Method has = ex.getClass().getDeclaredMethod("hasCommand", String.class);
        has.setAccessible(true);
        System.out.println("qa=" + soloMapling.qa.QaSuite.isEnabled()
                + " attackcapture=" + soloMapling.server.AttackCapture.isEnabled()
                + " commands qa/classtest/botshowcase=" + has.invoke(ex, "qa") + "/" + has.invoke(ex, "classtest")
                + "/" + has.invoke(ex, "botshowcase"));
        System.exit(0);
    }
}
