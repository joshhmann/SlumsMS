package soloMapling.qa;

/** A scenario or the suite refuses a request; the message goes to the GM as is. */
public class QaRefusal extends RuntimeException {
    public QaRefusal(String message) {
        super(message);
    }
}
