package sysprog.shared.helpers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sysprog.shared.dto.CommandDto;
import sysprog.shared.exceptions.AssemblerException;

public final class Parser {
    private static final Pattern WORD = Pattern.compile("[CXcx]\"[^\"]*(?:\"[^\"]*)*\"|\\S+");

    private Parser() {
    }

    public static List<List<String>> parseCode(String text) {
        List<List<String>> lines = new ArrayList<>();

        for (String line : text.split("\\r?\\n")) {
            List<String> words = new ArrayList<>();
            Matcher matcher = WORD.matcher(line);
            while (matcher.find())
                words.add(matcher.group());

            if (!words.isEmpty())
                lines.add(words);
        }

        return lines;
    }

    public static List<CommandDto> parseCommandTable(String text) {
        List<CommandDto> commands = new ArrayList<>();

        for (List<String> line : parseCode(text)) {
            if (line.size() != 3)
                throw new AssemblerException("Неправильный формат строки: " + String.join(" ", line));

            commands.add(new CommandDto(line.get(0), line.get(1), line.get(2)));
        }

        return commands;
    }
}
