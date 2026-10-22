package sysprog.shared;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import sysprog.shared.dto.CommandDto;
import sysprog.shared.exceptions.AssemblerException;
import sysprog.shared.helpers.Parser;
import sysprog.shared.structure.Command;

@DisplayName("Таблица кодов операций")
class CommandTableTest {

    private static void setTable(Assembler assembler, String table) {
        assembler.setCommands(Parser.parseCommandTable(table));
    }

    private static String render(List<Command> commands) {
        return commands.stream()
                .map(CommandDto::from)
                .map(c -> c.name() + " " + c.code() + " " + c.length())
                .collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("По умолчанию в ТКО от 8 до 10 команд, как требует задание")
    void defaultTableHasEightToTenCommands() {
        int size = new Assembler().getCommands().size();

        assertTrue(size >= 8 && size <= 10, "команд в ТКО: " + size);
    }

    @Test
    @DisplayName("В ТКО по умолчанию есть команды всех длин: 1, 2 и 4 байта")
    void defaultTableCoversAllLengths() {
        List<Integer> lengths = new Assembler().getCommands().stream().map(Command::length).collect(Collectors.toList());

        assertTrue(lengths.containsAll(List.of(1, 2, 4)));
    }

    @Test
    @DisplayName("ТКО, выведенная на экран, читается обратно без изменений")
    void displayedTableRoundTrips() {
        Assembler assembler = new Assembler();
        String displayed = render(assembler.getCommands());

        setTable(assembler, displayed);

        assertEquals(displayed, render(assembler.getCommands()));
    }

    @Test
    @DisplayName("Код и длина задаются в 16-ричном виде")
    void parsesCodeAsHex() {
        Assembler assembler = new Assembler();
        setTable(assembler, "JMP 1F 4");

        assertEquals(0x1F, assembler.getCommands().get(0).code());
    }

    @Test
    @DisplayName("Изменённая ТКО используется при ассемблировании")
    void usesEditedTable() {
        Assembler assembler = new Assembler();
        setTable(assembler, "GO 3F 4\nSTOP 0 1");

        assembler.reset(Parser.parseCode("P START 100\nL GO L\n STOP\n END"));
        List<String> code = assembler.run();

        assertEquals("T 000100 04 FD000100", code.get(1));
        assertEquals("T 000104 01 00", code.get(2));
    }

    @ParameterizedTest(name = "код {0} не помещается в 6 бит")
    @ValueSource(strings = {"40", "FF"})
    @DisplayName("Код команды больше 3F — ошибка")
    void rejectsCodeAbove3F(String code) {
        assertThrows(AssemblerException.class, () -> setTable(new Assembler(), "JMP " + code + " 4"));
    }

    @Test
    @DisplayName("Код 3F — максимально допустимый")
    void acceptsCode3F() {
        assertDoesNotThrow(() -> setTable(new Assembler(), "JMP 3F 4"));
    }

    @ParameterizedTest(name = "длина {0}")
    @ValueSource(strings = {"0", "3", "5"})
    @DisplayName("Длина команды может быть только 1, 2 или 4")
    void rejectsInvalidLength(String length) {
        assertThrows(AssemblerException.class, () -> setTable(new Assembler(), "JMP 01 " + length));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"JMP ZZ 4", "JMP 01 X", "1JMP 01 4", "JM-P 01 4", "ПРЫГ 01 4"})
    @DisplayName("Некорректные имя, код или длина — ошибка")
    void rejectsMalformedCommand(String line) {
        assertThrows(AssemblerException.class, () -> setTable(new Assembler(), line));
    }

    @Test
    @DisplayName("Имена команд должны быть уникальными (без учёта регистра)")
    void rejectsDuplicateNames() {
        assertThrows(AssemblerException.class, () -> setTable(new Assembler(), "JMP 01 4\njmp 02 4"));
    }

    @Test
    @DisplayName("Коды команд должны быть уникальными")
    void rejectsDuplicateCodes() {
        assertThrows(AssemblerException.class, () -> setTable(new Assembler(), "JMP 01 4\nGO 01 4"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"START", "end", "WORD", "R1", "r16"})
    @DisplayName("Имя команды не может совпадать с директивой или регистром")
    void rejectsReservedNames(String name) {
        assertThrows(AssemblerException.class, () -> setTable(new Assembler(), name + " 01 4"));
    }

    @Test
    @DisplayName("Пустая ТКО — ошибка")
    void rejectsEmptyTable() {
        assertThrows(AssemblerException.class, () -> setTable(new Assembler(), ""));
    }

    @Test
    @DisplayName("При ошибке в ТКО старая таблица сохраняется")
    void keepsPreviousTableOnError() {
        Assembler assembler = new Assembler();
        List<Command> before = assembler.getCommands();

        assertThrows(AssemblerException.class, () -> setTable(assembler, "JMP 01 4\nGO 01 4"));

        assertEquals(before, assembler.getCommands());
    }
}
