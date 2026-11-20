package sysprog.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sysprog.shared.AssemblerRun.bothPasses;
import static sysprog.shared.AssemblerRun.firstPass;
import static sysprog.shared.AssemblerRun.program;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import sysprog.app.Samples;
import sysprog.shared.exceptions.AssemblerException;
import sysprog.shared.helpers.Parser;

@DisplayName("Второй проход")
class SecondPassTest {

    private static String record(String source, int index) {
        return bothPasses(source).objectCode.get(index);
    }

    private static void assertSecondPassError(String source, String messagePart) {
        AssemblerRun run = firstPass(source);

        AssemblerException error = assertThrows(AssemblerException.class, run::secondPass);
        assertTrue(error.getMessage().contains(messagePart), "сообщение: " + error.getMessage());
    }

    @Nested
    @DisplayName("Прямая адресация и таблица настройки")
    class DirectAddressing {

        @Test
        @DisplayName("Пример «только прямая адресация»: заголовок, тело, записи настройки и точка входа")
        void directSample() {
            assertEquals(List.of(
                    "H PROG 000000 00001E",
                    "T 000000 04 0500000E",
                    "T 000004 03 000028",
                    "T 000007 06 48656C6C6F21",
                    "T 00000D 01 0C",
                    "T 00000E 04 09000004",
                    "T 000012 04 0D00000D",
                    "T 000016 02 1001",
                    "T 000018 04 15000004",
                    "T 00001C 02 18C8",
                    "M 000000",
                    "M 00000E",
                    "M 000012",
                    "M 000018",
                    "E 000000"
            ), bothPasses(Samples.DIRECT_SAMPLE).objectCode);
        }

        @Test
        @DisplayName("Таблица настройки — адреса команд, чей операнд-метка настраивается при загрузке")
        void relocationTable() {
            assertEquals(List.of("000000", "00000E", "000012", "000018"),
                    bothPasses(Samples.DIRECT_SAMPLE).relocationTable());
        }

        @Test
        @DisplayName("Числовой операнд команды длины 4 не настраивается")
        void numberIsNotRelocated() {
            AssemblerRun run = bothPasses(program("P START", " JMP 16", " END"));

            assertEquals("T 000000 04 04000010", run.objectCode.get(1));
            assertEquals(List.of(), run.relocationTable());
        }

        @Test
        @DisplayName("Регистры, байтовые значения и данные не попадают в таблицу настройки")
        void dataAndRegistersAreNotRelocated() {
            AssemblerRun run = bothPasses(program("P START", "A WORD 5", " ADD R1 R2", " INT 7", " RET", " END"));

            assertEquals(List.of(), run.relocationTable());
            assertTrue(run.objectCode.stream().noneMatch(r -> r.startsWith("M ")));
        }

        @Test
        @DisplayName("Ссылка вперёд подставляет адрес из ТСИ")
        void forwardReference() {
            assertEquals("T 000000 04 05000004", record(program("P START", " JMP NEXT", "NEXT RET", " END"), 1));
        }

        @Test
        @DisplayName("Неопределённая метка — ошибка второго прохода, первый проход проходит")
        void undefinedLabel() {
            assertSecondPassError(program("P START", " JMP NOWHERE", " END"), "Метка не найдена в ТСИ");
        }

        @Test
        @DisplayName("Операнд-метка совпадает с именем регистра и не определена — ошибка второго прохода")
        void operandResemblingReservedWordIsUndefined() {
            assertSecondPassError(program("P START", " JMP R1", " END"), "Метка не найдена в ТСИ");
        }
    }

    @Nested
    @DisplayName("Относительная адресация")
    class RelativeAddressing {

        @Test
        @DisplayName("Пример «только относительная адресация»: смещения от адреса следующей команды, таблица настройки пуста")
        void relativeSample() {
            AssemblerRun run = bothPasses(Samples.RELATIVE_SAMPLE);

            assertEquals(List.of(
                    "H PROG 000000 00001E",
                    "T 000000 04 0600000A",
                    "T 000004 03 000028",
                    "T 000007 06 48656C6C6F21",
                    "T 00000D 01 0C",
                    "T 00000E 04 0AFFFFF2",
                    "T 000012 04 0EFFFFF7",
                    "T 000016 02 1001",
                    "T 000018 04 16FFFFE8",
                    "T 00001C 02 18C8",
                    "E 000000"
            ), run.objectCode);
            assertEquals(List.of(), run.relocationTable());
        }

        @Test
        @DisplayName("Ссылка на саму себя — смещение -4 в дополнительном коде")
        void selfReference() {
            assertEquals("T 000000 04 06FFFFFC", record(program("P START", "L JMP [L]", " END"), 1));
        }

        @Test
        @DisplayName("Ссылка на следующую команду — нулевое смещение")
        void nextCommand() {
            assertEquals("T 000000 04 06000000", record(program("P START", " JMP [L]", "L RET", " END"), 1));
        }

        @Test
        @DisplayName("Метка в скобках не зависит от регистра букв")
        void caseInsensitive() {
            assertEquals("T 000000 04 06000000", record(program("P START", " JMP [loop]", "LOOP RET", " END"), 1));
        }

        @Test
        @DisplayName("Неопределённая метка в скобках — ошибка второго прохода")
        void undefinedLabel() {
            assertSecondPassError(program("P START", " JMP [NOWHERE]", " END"), "Метка не найдена в ТСИ");
        }

        private static String farJump(int distance) {
            List<String> lines = new ArrayList<>(List.of("P START", " JMP [FAR]"));
            for (int left = distance; left > 0; left -= 255)
                lines.add(" RESB " + Math.min(left, 255));
            lines.add("FAR RET");
            lines.add(" END");
            return program(lines.toArray(new String[0]));
        }

        private static String farJumpBack(int distance) {
            List<String> lines = new ArrayList<>(List.of("P START", "BACK RET"));
            for (int left = distance; left > 0; left -= 255)
                lines.add(" RESB " + Math.min(left, 255));
            lines.add(" JMP [BACK]");
            lines.add(" END");
            return program(lines.toArray(new String[0]));
        }

        private static String lastTextRecord(String source) {
            List<String> code = bothPasses(source).objectCode;
            return code.get(code.size() - 2);
        }

        @Test
        @DisplayName("Максимальное отрицательное смещение -800000")
        void minOffset() {
            assertEquals("T 7FFFFC 04 06800000", lastTextRecord(farJumpBack(0x7FFFFB)));
        }

        @Test
        @DisplayName("Смещение меньше -800000 не помещается в 3 байта со знаком — ошибка")
        void offsetTooSmall() {
            assertSecondPassError(farJumpBack(0x7FFFFC), "Смещение до метки не помещается в 3 байта");
        }

        @Test
        @DisplayName("Максимальное положительное смещение 7FFFFF")
        void maxOffset() {
            assertEquals("T 000000 04 067FFFFF", record(farJump(0x7FFFFF), 1));
        }

        @Test
        @DisplayName("Смещение больше 7FFFFF не помещается в 3 байта со знаком — ошибка")
        void offsetTooLarge() {
            assertSecondPassError(farJump(0x800000), "Смещение до метки не помещается в 3 байта");
        }
    }

    @Nested
    @DisplayName("Смешанная адресация")
    class MixedAddressing {

        @Test
        @DisplayName("В таблицу настройки попадают только команды с прямой адресацией")
        void onlyDirectIsRelocated() {
            AssemblerRun run = bothPasses(Samples.MIXED_SAMPLE);

            assertEquals(List.of("00000E", "000018"), run.relocationTable());
            assertEquals(List.of(
                    "H PROG 000000 000022",
                    "T 000000 04 0600000A",
                    "T 000004 03 000028",
                    "T 000007 06 48656C6C6F21",
                    "T 00000D 01 0C",
                    "T 00000E 04 09000004",
                    "T 000012 04 0EFFFFF7",
                    "T 000016 02 1001",
                    "T 000018 04 15000004",
                    "T 00001C 02 18C8",
                    "T 00001E 04 06FFFFEC",
                    "M 00000E",
                    "M 000018",
                    "E 000000"
            ), run.objectCode);
        }

        @Test
        @DisplayName("Пример со всеми командами ТКО")
        void allCommandsSample() {
            assertEquals(List.of(
                    "H MAIN 000000 000020",
                    "T 000000 04 26000002",
                    "T 000004 02 1821",
                    "T 000006 04 09000017",
                    "T 00000A 04 0D00001A",
                    "T 00000E 02 2001",
                    "T 000010 02 1C01",
                    "T 000012 04 16000007",
                    "T 000016 01 28",
                    "T 000017 03 000007",
                    "T 00001A 03 000003",
                    "T 00001D 03",
                    "M 000006",
                    "M 00000A",
                    "E 000000"
            ), bothPasses(Samples.ALL_COMMANDS_SAMPLE).objectCode);
        }
    }

    @Nested
    @DisplayName("Кодирование команд и данных")
    class Encoding {

        @Test
        @DisplayName("Заголовок: имя программы, начальный адрес 0 и длина")
        void header() {
            assertEquals("H SUM 000000 000007", record(program("SUM START", " JMP 1", " ADD R1 R2", " RET", " END"), 0));
        }

        @Test
        @DisplayName("Регистры кодируются номерами 0..F")
        void registers() {
            assertEquals("T 000000 02 10F0", record(program("P START", " ADD R16 R1", " END"), 1));
        }

        @Test
        @DisplayName("Команда длины 1")
        void lengthOne() {
            assertEquals("T 000000 01 28", record(program("P START", " RET", " END"), 1));
        }

        @Test
        @DisplayName("BYTE X\"...\": длина в байтах и сами байты")
        void hexString() {
            assertEquals("T 000000 04 2F4C008A", record(program("P START", "A BYTE X\"2F4C008A\"", " END"), 1));
        }

        @Test
        @DisplayName("BYTE C\"...\": ASCII-коды символов, включая пробел и кавычки")
        void characterString() {
            assertEquals("T 000000 03 612062", record(program("P START", "A BYTE C\"a b\"", " END"), 1));
            assertEquals("T 000000 03 612262", record(program("P START", "A BYTE C\"a\"b\"", " END"), 1));
        }

        @Test
        @DisplayName("RESB/RESW: только длина резервируемой области")
        void reserve() {
            List<String> code = bothPasses(program("P START", "A RESB 10", "B RESW 2", " END")).objectCode;

            assertEquals("T 000000 0A", code.get(1));
            assertEquals("T 00000A 06", code.get(2));
        }
    }

    @Test
    @DisplayName("Повторный второй проход не накапливает таблицу настройки, первый проход её очищает")
    void relocationTableIsReset() {
        AssemblerRun run = bothPasses(Samples.DIRECT_SAMPLE).secondPass();
        assertEquals(4, run.relocationTable().size());

        run.assembler.firstPass(Parser.parseCode(Samples.DIRECT_SAMPLE));
        assertEquals(List.of(), run.relocationTable());
    }

    @Test
    @DisplayName("Все примеры без ошибок проходят оба прохода")
    void correctSamplesAssemble() {
        for (Map.Entry<String, String> sample : Samples.ALL.entrySet()) {
            if (!sample.getKey().contains("без ошибок")) continue;

            List<String> code = bothPasses(sample.getValue()).objectCode;
            assertTrue(code.get(0).startsWith("H "), sample.getKey());
            assertTrue(code.get(code.size() - 1).startsWith("E "), sample.getKey());
        }
    }

    @Test
    @DisplayName("Примеры с ошибками падают на нужном проходе")
    void erroneousSamplesFail() {
        for (Map.Entry<String, String> sample : Samples.ALL.entrySet()) {
            String name = sample.getKey();
            if (name.contains("без ошибок")) continue;

            if (name.startsWith("Ошибка 2-го прохода")) {
                AssemblerRun run = firstPass(sample.getValue());
                assertThrows(AssemblerException.class, run::secondPass, name);
            } else {
                assertThrows(AssemblerException.class, () -> firstPass(sample.getValue()), name);
            }
        }
    }
}
