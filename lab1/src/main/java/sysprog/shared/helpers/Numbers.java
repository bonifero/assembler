package sysprog.shared.helpers;

import java.util.regex.Pattern;

public final class Numbers {
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,9}");
    private static final Pattern HEX = Pattern.compile("[0-9A-Fa-f]{1,7}");

    private Numbers() {
    }

    public static Integer parseDecimal(String text) {
        return text != null && DECIMAL.matcher(text).matches() ? Integer.parseInt(text) : null;
    }

    public static Integer parseHex(String text) {
        return text != null && HEX.matcher(text).matches() ? Integer.parseInt(text, 16) : null;
    }

    public static String hex(int value, int digits) {
        return String.format("%0" + digits + "X", value);
    }
}
