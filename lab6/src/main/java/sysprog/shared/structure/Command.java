package sysprog.shared.structure;

import java.util.Set;
import java.util.regex.Pattern;

import sysprog.shared.dto.CommandDto;
import sysprog.shared.exceptions.AssemblerException;
import sysprog.shared.helpers.Numbers;

public record Command(String name, int code, int length) {
    public static final int MAX_CODE = 0x3F;

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
    private static final Set<Integer> LENGTHS = Set.of(1, 2, 4);

    public static Command fromDto(CommandDto dto) {
        String text = dto.name() + " " + dto.code() + " " + dto.length();

        if (!NAME.matcher(dto.name()).matches())
            throw new AssemblerException("Название команды должно начинаться с латинской буквы и состоять из латинских букв и цифр: " + text);

        Integer code = Numbers.parseHex(dto.code());
        if (code == null)
            throw new AssemblerException("Код команды должен быть целым числом в 16-ричном формате: " + text);
        if (code > MAX_CODE)
            throw new AssemblerException("Код команды должен быть значением от 00 до 3F (6 бит): " + text);

        Integer length = Numbers.parseHex(dto.length());
        if (length == null)
            throw new AssemblerException("Длина команды должна быть целым числом в 16-ричном формате: " + text);
        if (!LENGTHS.contains(length))
            throw new AssemblerException("Длина команды должна быть 1, 2 или 4: " + text);

        return new Command(dto.name(), code, length);
    }
}
