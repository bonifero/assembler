package sysprog.shared.helpers;

import java.math.BigInteger;
import java.util.regex.Pattern;

public final class Numbers {
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+");
    private static final Pattern HEX = Pattern.compile("[0-9A-Fa-f]+");
    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);

    private Numbers() {
    }

    public static Integer parseDecimal(String text) {
        return text != null && DECIMAL.matcher(text).matches() ? saturate(new BigInteger(text)) : null;
    }

    public static Integer parseHex(String text) {
        return text != null && HEX.matcher(text).matches() ? saturate(new BigInteger(text, 16)) : null;
    }

    public static String hex(int value, int digits) {
        return String.format("%0" + digits + "X", value);
    }

    // Ведущие нули допустимы; слишком большие числа сводятся к Integer.MAX_VALUE,
    // чтобы вызывающий код выдал ошибку выхода за диапазон, а не ошибку формата.
    private static int saturate(BigInteger value) {
        return value.min(INT_MAX).intValue();
    }
}
