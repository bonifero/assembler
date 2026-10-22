package sysprog.shared.helpers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import sysprog.shared.exceptions.AssemblerException;

public final class ObjectModuleFile {
    public static final String EXTENSION = "txt";

    private ObjectModuleFile() {
    }

    public static Path withExtension(Path path) {
        String name = path.getFileName().toString();
        return name.contains(".") ? path : path.resolveSibling(name + "." + EXTENSION);
    }

    public static void save(Path path, List<String> objectCode) {
        if (objectCode.isEmpty())
            throw new AssemblerException("Нечего сохранять: сначала выполните второй проход без ошибок");

        try {
            Files.write(path, objectCode, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AssemblerException("Не удалось сохранить объектный модуль в файл " + path + ": " + ex.getMessage());
        }
    }
}
