package sysprog.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sysprog.shared.AssemblerRun.bothPasses;
import static sysprog.shared.AssemblerRun.firstPass;
import static sysprog.shared.AssemblerRun.program;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sysprog.app.Samples;
import sysprog.shared.exceptions.AssemblerException;
import sysprog.shared.helpers.ObjectModuleFile;
import sysprog.shared.helpers.Parser;

@DisplayName("Раздельное ассемблирование: EXTDEF и EXTREF")
class ExternalLinkingTest {

    private static void assertFirstPassError(String source, String messagePart) {
        AssemblerException error = assertThrows(AssemblerException.class, () -> firstPass(source));
        assertTrue(error.getMessage().contains(messagePart), "сообщение: " + error.getMessage());
    }

    private static void assertFirstPassError(AddressingMode mode, String source, String messagePart) {
        Assembler assembler = new Assembler();
        assembler.setAddressingMode(mode);

        AssemblerException error = assertThrows(AssemblerException.class,
                () -> assembler.firstPass(Parser.parseCode(source)));
        assertTrue(error.getMessage().contains(messagePart), "сообщение: " + error.getMessage());
    }

    @Nested
    @DisplayName("Пример по умолчанию")
    class DefaultSample {

        @Test
        @DisplayName("Вспомогательная таблица содержит EXTDEF и EXTREF для второго прохода")
        void intermediateTable() {
            assertEquals(List.of(
                    "FIRST START 000000",
                    "EXTDEF BUF STR1",
                    "EXTREF STR2",
                    "000000 09 STR3",
                    "000004 0D STR2",
                    "000008 10 R1 R2",
                    "00000A 15 REZ",
                    "00000E 05 PROC",
                    "000012 WORD 000003",
                    "000015 WORD 000001",
                    "000018 RESW 01",
                    "00001B BYTE C\"Hello!\"",
                    "END 000000"
            ), firstPass(Samples.DEFAULT_SAMPLE).firstPass);
        }

        @Test
        @DisplayName("ТСИ отмечает внешние имена признаком 1, внешние ссылки в неё не попадают")
        void symbolTableExternalFlag() {
            assertEquals(List.of(
                    "PROC 000000 0",
                    "STR1 000012 1",
                    "STR3 000015 0",
                    "REZ 000018 0",
                    "BUF 00001B 1"
            ), firstPass(Samples.DEFAULT_SAMPLE).symbolTableWithExternalFlag());
        }

        @Test
        @DisplayName("Объектный модуль: заголовок, внешние имена, внешние ссылки, таблица настройки, тело")
        void objectModule() {
            assertEquals(List.of(
                    "H FIRST 000000 000021",
                    "D BUF 00001B",
                    "D STR1 000012",
                    "R STR2",
                    "M 000000",
                    "M 000004 STR2",
                    "M 00000A",
                    "M 00000E",
                    "T 000000 04 09000015",
                    "T 000004 04 0D000000",
                    "T 000008 02 1001",
                    "T 00000A 04 15000018",
                    "T 00000E 04 05000000",
                    "T 000012 03 000003",
                    "T 000015 03 000001",
                    "T 000018 03",
                    "T 00001B 06 48656C6C6F21",
                    "E 000000"
            ), bothPasses(Samples.DEFAULT_SAMPLE).objectCode);
        }

        @Test
        @DisplayName("Таблица настройки из двух столбцов: адрес команды и имя внешней ссылки")
        void relocationTable() {
            assertEquals(List.of("000000", "000004 STR2", "00000A", "00000E"),
                    bothPasses(Samples.DEFAULT_SAMPLE).relocationTable());
        }

        @Test
        @DisplayName("Таблица внешних имён и список внешних ссылок формируются на втором проходе")
        void externalTables() {
            AssemblerRun run = firstPass(Samples.DEFAULT_SAMPLE);
            assertEquals(List.of(), run.externalNames());
            assertEquals(List.of(), run.assembler.getExternalReferences());

            run.secondPass();
            assertEquals(List.of("BUF 00001B", "STR1 000012"), run.externalNames());
            assertEquals(List.of("STR2"), run.assembler.getExternalReferences());
        }
    }

    @Nested
    @DisplayName("Адресация внешних ссылок")
    class Addressing {

        @Test
        @DisplayName("Смешанный пример: относительные операнды не настраиваются, внешние ссылки дают 000000")
        void mixedSample() {
            AssemblerRun run = bothPasses(Samples.EXTERNAL_MIXED_SAMPLE);

            assertEquals(List.of("000004 STR2", "00000A", "00000E SUBR"), run.relocationTable());
            assertEquals(List.of("STR2", "SUBR"), run.assembler.getExternalReferences());
            assertTrue(run.objectCode.contains("T 00000E 04 25000000"));
        }

        @Test
        @DisplayName("Внешние имена допустимы в режиме «только относительная адресация»")
        void relativeOnlySample() {
            Assembler assembler = new Assembler();
            assembler.setAddressingMode(AddressingMode.RELATIVE_ONLY);
            List<String> intermediate = assembler.firstPass(Parser.parseCode(Samples.EXTERNAL_RELATIVE_SAMPLE));
            List<String> code = assembler.secondPass(Parser.parseCode(String.join("\n", intermediate)));

            assertEquals(List.of(), assembler.getRelocationTable());
            assertTrue(code.contains("D STR1 000012"));
        }

        @Test
        @DisplayName("Внешнюю ссылку нельзя адресовать относительно")
        void relativeExternalReference() {
            assertFirstPassError(Samples.RELATIVE_EXTERNAL_REFERENCE_SAMPLE,
                    "Внешнюю ссылку STR2 нельзя адресовать относительно");
        }

        @Test
        @DisplayName("В режиме «только относительная» внешнюю ссылку использовать нельзя — она адресуется прямо")
        void externalReferenceInRelativeOnlyMode() {
            assertFirstPassError(AddressingMode.RELATIVE_ONLY,
                    program("P START", " EXTREF X", " JMP X", " END"),
                    "Внешняя ссылка X адресуется только прямо, а прямая адресация запрещена в режиме"
                            + " «Только относительная адресация»");
        }

        @Test
        @DisplayName("Объявленная, но неиспользуемая внешняя ссылка попадает в список без записей настройки")
        void unusedReference() {
            AssemblerRun run = bothPasses(program("P START", " EXTREF X", " RET", " END"));

            assertEquals(List.of("H P 000000 000001", "R X", "T 000000 01 28", "E 000000"), run.objectCode);
            assertEquals(List.of(), run.relocationTable());
        }

        @Test
        @DisplayName("Точкой входа не может быть внешняя ссылка")
        void entryPointIsExternal() {
            assertFirstPassError(program("P START", " EXTREF X", " RET", " END X"),
                    "Точкой входа не может быть внешняя ссылка X");
        }
    }

    @Nested
    @DisplayName("Директивы EXTDEF и EXTREF")
    class Directives {

        @Test
        @DisplayName("Несколько директив подряд накапливают имена; регистр букв не важен")
        void severalDirectives() {
            AssemblerRun run = bothPasses(program("P START", " extdef a", " EXTDEF b", " extref x  y",
                    "A JMP x", "B CALL Y", " END"));

            assertEquals(List.of("A 000000", "B 000004"), run.externalNames());
            assertEquals(List.of("X", "Y"), run.assembler.getExternalReferences());
            assertEquals(List.of("000000 X", "000004 Y"), run.relocationTable());
        }

        @Test
        @DisplayName("Перед директивой не должно быть метки")
        void labelBefore() {
            assertFirstPassError(program("P START", "L EXTDEF A", "A RET", " END"),
                    "Перед директивой EXTDEF не должно быть метки");
            assertFirstPassError(program("P START", "L EXTREF A B", " RET", " END"),
                    "Перед директивой EXTREF не должно быть метки");
        }

        @Test
        @DisplayName("Директивы стоят сразу после START, до первой команды")
        void afterCommand() {
            assertFirstPassError(Samples.EXTERNAL_AFTER_COMMAND_SAMPLE,
                    "Директива EXTREF должна стоять сразу после START");
        }

        @Test
        @DisplayName("Имя из EXTDEF должно быть определено в программе")
        void undefinedExternalName() {
            assertFirstPassError(Samples.UNDEFINED_EXTERNAL_NAME_SAMPLE,
                    "Внешнее имя STR9, объявленное в EXTDEF, не определено в программе");
        }

        @Test
        @DisplayName("Метка не может совпадать с внешней ссылкой")
        void labelIsExternalReference() {
            assertFirstPassError(Samples.EXTERNAL_REFERENCE_LABEL_SAMPLE,
                    "Метка STR2 объявлена в EXTREF как внешняя ссылка");
        }

        @Test
        @DisplayName("Повторное объявление имени — ошибка")
        void duplicates() {
            assertFirstPassError(program("P START", " EXTDEF A A", "A RET", " END"),
                    "Имя A уже объявлено в EXTDEF");
            assertFirstPassError(program("P START", " EXTREF X", " EXTREF x", " RET", " END"),
                    "Имя X уже объявлено в EXTREF");
            assertFirstPassError(program("P START", " EXTDEF A", " EXTREF A", "A RET", " END"),
                    "Имя A уже объявлено в EXTDEF");
        }

        @Test
        @DisplayName("Операнд должен быть допустимым именем")
        void invalidNames() {
            assertFirstPassError(program("P START", " EXTDEF 1A", " RET", " END"),
                    "Операнд директивы EXTDEF должен быть именем");
            assertFirstPassError(program("P START", " EXTREF R1", " RET", " END"),
                    "Имя метки не должно совпадать с именем регистра");
            assertFirstPassError(program("P START", " EXTREF JMP", " RET", " END"),
                    "Имя метки не должно совпадать с именем команды");
            assertFirstPassError(program("P START", " EXTDEF VERYLONGNAME", " RET", " END"),
                    "Длина метки не должна превышать 10 символов");
        }

        @Test
        @DisplayName("Больше двух имён в одной директиве — ошибка числа операндов")
        void tooManyNames() {
            assertFirstPassError(program("P START", " EXTDEF A B C", " RET", " END"),
                    "Неверное количество операндов для EXTDEF");
        }

        @Test
        @DisplayName("Без операндов — ошибка числа операндов")
        void noNames() {
            assertFirstPassError(program("P START", " EXTREF", " RET", " END"),
                    "Неверное количество операндов для EXTREF");
        }

        @Test
        @DisplayName("Повторный первый проход не сохраняет объявления предыдущей программы")
        void declarationsAreReset() {
            AssemblerRun run = bothPasses(Samples.DEFAULT_SAMPLE);
            run.assembler.firstPass(Parser.parseCode(program("P START", "STR2 RET", " END")));

            assertEquals(List.of(), run.externalNames());
            assertEquals(List.of(), run.relocationTable());
            assertEquals(List.of(), run.assembler.getExternalReferences());
        }
    }

    @Test
    @DisplayName("Лишний операнд команды сообщает о количестве операндов, а не о метке")
    void extraOperandMessage() {
        assertFirstPassError(program("P START", " ADD R1 R2 R3", " END"), "Неверное количество операндов для ADD");
    }

    @Nested
    @DisplayName("Сохранение объектного модуля в файл")
    class SaveToFile {

        @Test
        @DisplayName("Файл содержит объектный модуль построчно в полном перемещаемом формате")
        void save(@TempDir Path dir) throws IOException {
            List<String> code = bothPasses(Samples.DEFAULT_SAMPLE).objectCode;
            Path file = dir.resolve("first.txt");

            ObjectModuleFile.save(file, code);

            assertEquals(code, Files.readAllLines(file, StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("Без расширения добавляется .txt, указанное расширение сохраняется")
        void extension(@TempDir Path dir) {
            assertEquals(dir.resolve("first.txt"), ObjectModuleFile.withExtension(dir.resolve("first")));
            assertEquals(dir.resolve("first.obj"), ObjectModuleFile.withExtension(dir.resolve("first.obj")));
        }

        @Test
        @DisplayName("Пустой модуль не сохраняется")
        void emptyModule(@TempDir Path dir) {
            assertThrows(AssemblerException.class, () -> ObjectModuleFile.save(dir.resolve("x.txt"), List.of()));
        }
    }
}
