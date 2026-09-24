package sysprog.shared.helpers;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class Operands {
    private static final Pattern REGISTER = Pattern.compile("R(?:[1-9]|1[0-6])");
    private static final Pattern HEX_DIGITS = Pattern.compile("[0-9A-Fa-f]+");
    private static final Pattern ASCII_WITHOUT_QUOTES = Pattern.compile("[\\x00-\\x7F&&[^\"]]+");

    private Operands() {
    }

    public static boolean isRegister(String operand) {
        return operand != null && REGISTER.matcher(operand.toUpperCase()).matches();
    }

    public static int registerNumber(String register) {
        return Integer.parseInt(register.substring(1)) - 1;
    }

    public static boolean isCharacterString(String operand) {
        return isQuoted(operand, 'C') && ASCII_WITHOUT_QUOTES.matcher(content(operand)).matches();
    }

    public static boolean isHexString(String operand) {
        if (!isQuoted(operand, 'X')) return false;

        String digits = content(operand);
        return digits.length() % 2 == 0 && HEX_DIGITS.matcher(digits).matches();
    }

    public static String content(String quoted) {
        return quoted.substring(2, quoted.length() - 1);
    }

    public static String toAsciiHex(String text) {
        StringBuilder result = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.US_ASCII))
            result.append(Numbers.hex(b & 0xFF, 2));
        return result.toString();
    }

    private static boolean isQuoted(String operand, char prefix) {
        return operand != null
                && operand.length() >= 4
                && Character.toUpperCase(operand.charAt(0)) == prefix
                && operand.charAt(1) == '"'
                && operand.endsWith("\"");
    }
}
