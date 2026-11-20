package sysprog.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sysprog.shared.AssemblerRun.bothPasses;
import static sysprog.shared.AssemblerRun.firstPass;
import static sysprog.shared.AssemblerRun.program;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import sysprog.app.Samples;
import sysprog.shared.exceptions.AssemblerException;
import sysprog.shared.structure.Section;

@DisplayName("Управляющие секции CSECT")
class ControlSectionTest {

    private static void assertFirstPassError(String source, String messagePart) {
        AssemblerException error = assertThrows(AssemblerException.class, () -> firstPass(source));
        assertTrue(error.getMessage().contains(messagePart), "сообщение: " + error.getMessage());
    }

    private static void assertSecondPassError(String source, String messagePart) {
        AssemblerRun run = firstPass(source);
        AssemblerException error = assertThrows(AssemblerException.class, run::secondPass);
        assertTrue(error.getMessage().contains(messagePart), "сообщение: " + error.getMessage());
    }

    @Nested
    @DisplayName("Пример MAIN и LIB")
    class MainAndLib {

        @Test
        @DisplayName("Вспомогательная таблица содержит строку CSECT, адреса LIB снова от нуля")
        void intermediateTable() {
            assertEquals(List.of(
                    "MAIN START 000000",
                    "EXTREF PRINT",
                    "000000 25 PRINT",
                    "000004 28",
                    "LIB CSECT 000000",
                    "EXTDEF PRINT",
                    "000000 18 01",
                    "000002 28",
                    "END 000000"
            ), firstPass(Samples.CONTROL_SECTION_SAMPLE).firstPass);
        }

        @Test
        @DisplayName("Операнд CSECT задаёт точку входа предыдущей секции, END — последней")
        void sections() {
            assertEquals(List.of(new Section("MAIN", 5, 0), new Section("LIB", 3, 0)),
                    firstPass(Samples.CONTROL_SECTION_SAMPLE).assembler.getSections());
        }

        @Test
        @DisplayName("Каждая секция даёт свой модуль: H, D, R, M, T, E")
        void objectCode() {
            assertEquals(List.of(
                    "H MAIN 000000 000005",
                    "R PRINT",
                    "T 000000 04 25000000",
                    "T 000004 01 28",
                    "M 000000 PRINT",
                    "E 000000",
                    "H LIB 000000 000003",
                    "D PRINT 000000",
                    "T 000000 02 1801",
                    "T 000002 01 28",
                    "E 000000"
            ), bothPasses(Samples.CONTROL_SECTION_SAMPLE).objectCode);
        }
    }

    @Test
    @DisplayName("Точка входа первой секции задаётся меткой в операнде CSECT")
    void entryFromCsectOperand() {
        AssemblerRun run = firstPass(program("A START", " RET", "B RET", "C CSECT B", " RET", " END"));
        assertEquals(1, run.assembler.getSections().get(0).entryAddress());
    }

    @Test
    @DisplayName("Одинаковые локальные метки в разных секциях не конфликтуют")
    void sameLocalLabels() {
        AssemblerRun run = bothPasses(Samples.SAME_LABEL_IN_SECTIONS_SAMPLE);
        assertEquals(List.of("DATA 000000", "DATA 000000"), run.symbolTable());
        assertTrue(run.objectCode.contains("T 000003 04 06FFFFF9"));
    }

    @Test
    @DisplayName("Ссылка на внешнее имя из другой секции попадает в таблицу настройки своей секции")
    void relocationBySection() {
        AssemblerRun run = bothPasses(Samples.SECTIONS_SAMPLE);
        assertEquals(List.of("000018 D546", "000000 D4"), run.relocationTable());
        assertEquals(List.of("PROG", "A2"),
                run.assembler.getRelocationTable().stream().map(entry -> entry.section()).toList());
    }

    @Test
    @DisplayName("Одну программу без CSECT ассемблирует как одну секцию")
    void singleSection() {
        AssemblerRun run = firstPass(program("P START", " RET", " END"));
        assertEquals(List.of(new Section("P", 1, 0)), run.assembler.getSections());
    }

    @Nested
    @DisplayName("Ошибки")
    class Errors {

        @Test
        @DisplayName("Перед CSECT нужна метка")
        void missingName() {
            assertFirstPassError(program("P START", " RET", " CSECT", " RET", " END"), "имя секции");
        }

        @Test
        @DisplayName("Имена секций не повторяются")
        void duplicateName() {
            assertFirstPassError(Samples.DUPLICATE_SECTION_SAMPLE, "Имя секции PROG уже используется");
        }

        @Test
        @DisplayName("Пустая секция")
        void emptySection() {
            assertFirstPassError(program("P START", " RET", "B CSECT", " END"), "Секция B не содержит");
        }

        @Test
        @DisplayName("Одно внешнее имя экспортирует только одна секция")
        void duplicateExport() {
            assertFirstPassError(Samples.DUPLICATE_EXPORT_SAMPLE, "уже экспортируется другой секцией");
        }

        @Test
        @DisplayName("EXTDEF второй секции должен быть определён в ней же")
        void undefinedExportInSecondSection() {
            assertFirstPassError(program("P START", " RET", "B CSECT", " EXTDEF X", " RET", " END"),
                    "не определено в программе (секция B)");
        }

        @Test
        @DisplayName("EXTREF после команды второй секции")
        void declarationAfterCommand() {
            assertFirstPassError(program("P START", " RET", "B CSECT", " RET", " EXTREF X", " END"),
                    "сразу после START или CSECT");
        }

        @Test
        @DisplayName("Метка другой секции не видна без EXTDEF/EXTREF")
        void crossSectionLabel() {
            assertSecondPassError(Samples.CROSS_SECTION_SAMPLE, "Метка не найдена в ТСИ");
        }
    }
}
