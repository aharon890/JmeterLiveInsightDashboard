package com.jmeterinsight.livedashboard.server;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * What {@code live.dashboard.access.token} asks for: the token of the dashboard's access link, or none.
 *
 * <p>The property has three kinds of value. {@code generate} makes a new random token for this JMeter
 * process. {@code not_required} says in so many words that a dashboard bound beyond localhost is meant to
 * be open. Anything else is the operator's own token, which lets the script that starts several plans
 * give them all one link, so a browser tab keeps following them. Unset means no token, as before the
 * property existed.
 */
public final class AccessLink {

    public static final String GENERATE = "generate";
    public static final String NOT_REQUIRED = "not_required";

    // What a token may hold: it travels in a URL and in a cookie, so nothing either one would have to escape.
    private static final Pattern OWN_TOKEN = Pattern.compile("[A-Za-z0-9._~-]{16,128}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The token requests must carry; null when no access link is asked for. */
    public final String token;
    /** True when the property said {@code not_required}: an open dashboard beyond localhost is intended. */
    public final boolean declaredNotRequired;
    /** A sentence for the log when the property's value could not be used as written; otherwise null. */
    public final String note;

    private AccessLink(String token, boolean declaredNotRequired, String note) {
        this.token = token;
        this.declaredNotRequired = declaredNotRequired;
        this.note = note;
    }

    /** Reads the property's value. Never throws: a value that is no usable token becomes a generated one. */
    public static AccessLink from(String configured) {
        String value = configured == null ? "" : configured.trim();
        if (value.isEmpty()) return new AccessLink(null, false, null);
        if (NOT_REQUIRED.equalsIgnoreCase(value)) return new AccessLink(null, true, null);
        if (GENERATE.equalsIgnoreCase(value)) return new AccessLink(newToken(), false, null);
        if (OWN_TOKEN.matcher(value).matches()) return new AccessLink(value, false, null);
        // Starting open would be the unsafe reading of a typo, and starting no dashboard would lose the run's
        // live view. A generated token is safe and still usable: the link is in the log.
        return new AccessLink(newToken(), false, "live.dashboard.access.token cannot be used as a token: a token "
                + "of your own is 16 to 128 characters of letters, digits, '-', '_', '.' or '~'. A generated "
                + "token is used instead; the link is on the dashboard's start line.");
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return hex.toString();
    }
}
