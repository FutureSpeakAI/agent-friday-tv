package ai.futurespeak.friday.tv.brain;

/** A failed turn, with a sentence fit to show on the TV. Never carries a key or a reply. */
public final class BrainException extends Exception {
    public enum Kind { NO_KEY, AUTH, RATE_LIMIT, OVERLOADED, NETWORK, BAD_REQUEST, SERVER, NOT_PAIRED, OTHER }

    public final Kind kind;
    public final int httpStatus;

    public BrainException(Kind kind, int httpStatus, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    /** What the TV says out loud and on screen. */
    public String friendly() {
        switch (kind) {
            case NO_KEY: return "I'm not set up yet. A grown-up needs to add my key from the PC.";
            case AUTH: return "My key was refused. A grown-up needs to check it on the PC.";
            case RATE_LIMIT: return "I'm getting too many questions at once. Try again in a minute.";
            case OVERLOADED: return "Claude is very busy right now. Try again in a moment.";
            case NETWORK: return "I can't reach the internet right now.";
            case NOT_PAIRED: return "Friday desktop isn't paired with this TV yet.";
            default: return "Something went wrong on my side. Try asking again.";
        }
    }

    public static BrainException fromStatus(int status, String errorType) {
        if (status == 401 || status == 403) return new BrainException(Kind.AUTH, status, "authentication failed (" + status + ")");
        if (status == 429) return new BrainException(Kind.RATE_LIMIT, status, "rate limited");
        if (status == 529 || "overloaded_error".equals(errorType)) return new BrainException(Kind.OVERLOADED, status, "overloaded");
        if (status >= 400 && status < 500) return new BrainException(Kind.BAD_REQUEST, status, "request rejected (" + status + (errorType == null ? "" : ", " + errorType) + ")");
        if (status >= 500) return new BrainException(Kind.SERVER, status, "server error (" + status + ")");
        return new BrainException(Kind.OTHER, status, "unexpected status " + status);
    }
}
