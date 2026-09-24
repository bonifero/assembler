package sysprog.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sysprog.shared.AssemblerRun.bothPasses;
import static sysprog.shared.AssemblerRun.firstPass;
import static sysprog.shared.AssemblerRun.program;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sysprog.app.Samples;
import sysprog.shared.exceptions.AssemblerException;

@DisplayName("Второй проход")
class SecondPassTest {

    private static String record(String source, int index) {
        return bothPasses(source).objectCode.get(index);
    }

    @Test
    @DisplayName("Пример по умолчанию: заголовок, тело и точка входа")
    void defaultSample() {
        assertEquals(List.of(
                "H PROG 000100 00001E",
                "T 000100 04 0500010E",
                "T 000104 03 000028",
                "T 000107 06 48656C6C6F21",
                "T 00010D 01 0C",
                "T 00010E 04 09000104",
                "T 000112 04 0D00010D",
                "T 000116 02 1001",
                "T 000118 04 15000104",
                "T 00011C 02 18C8",
                "E 000100"
        ), bothPasses(Samples.DEFAULT_SAMPLE).objectCode);
    }

    @Test
    @DisplayName("Заголовок: имя программы, адрес загрузки и длина")
    void header() {
        assertEquals("H SUM 000ABC 000007", record(program("SUM START ABC", " JMP 1", " ADD R1 R2", " RET", " END"), 0));
    }

    @Test
    @DisplayName("Смена адреса загрузки меняет адреса в коде")
    void loadAddressAffectsCode() {
        String source = program("P START 100", "L JMP L", " END");

        assertEquals("T 000100 04 05000100", bothPasses(source).objectCode.get(1));
        assertEquals("T 002000 04 05002000", bothPasses(source, "2000").objectCode.get(1));
    }

    @Test
    @DisplayName("Ссылка вперёд подставляет адрес из ТСИ")
    void forwardReference() {
        assertEquals("T 000100 04 05000104", record(program("P START 100", " JMP NEXT", "NEXT RET", " END"), 1));
    }

    @Test
    @DisplayName("Регистры кодируются номерами 0..F")
    void registers() {
        assertEquals("T 000100 02 10F0", record(program("P START 100", " ADD R16 R1", " END"), 1));
    }

    @Test
    @DisplayName("Команда длины 1")
    void lengthOne() {
        assertEquals("T 000100 01 28", record(program("P START 100", " RET", " END"), 1));
    }

    @Test
    @DisplayName("Команда длины 4 с числовым операндом")
    void lengthFourNumber() {
        assertEquals("T 000100 04 04000010", record(program("P START 100", " JMP 16", " END"), 1));
    }

    @Test
    @DisplayName("BYTE X\"...\": длина в байтах и сами байты")
    void hexString() {
        assertEquals("T 000100 04 2F4C008A", record(program("P START 100", "A BYTE X\"2F4C008A\"", " END"), 1));
    }

    @Test
    @DisplayName("BYTE C\"...\": ASCII-коды символов, включая пробел")
    void characterString() {
        assertEquals("T 000100 03 612062", record(program("P START 100", "A BYTE C\"a b\"", " END"), 1));
    }

    @Test
    @DisplayName("RESB/RESW: только длина резервируемой области")
    void reserve() {
        List<String> code = bothPasses(program("P START 100", "A RESB 10", "B RESW 2", " END")).objectCode;

        assertEquals("T 000100 0A", code.get(1));
        assertEquals("T 00010A 06", code.get(2));
    }

    @Test
    @DisplayName("Неопределённая метка — ошибка второго прохода, первый проход проходит")
    void undefinedLabel() {
        AssemblerRun run = firstPass(program("P START 100", " JMP NOWHERE", " END"));

        AssemblerException error = assertThrows(AssemblerException.class, run::secondPass);
        assertTrue(error.getMessage().contains("Метка не найдена в ТСИ"));
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

    @Test
    @DisplayName("Пример со всеми командами ТКО")
    void allCommandsSample() {
        assertEquals(List.of(
                "H MAIN 002000 000020",
                "T 002000 04 25002006",
                "T 002004 02 1821",
                "T 002006 04 09002017",
                "T 00200A 04 0D00201A",
                "T 00200E 02 2001",
                "T 002010 02 1C01",
                "T 002012 04 1500201D",
                "T 002016 01 28",
                "T 002017 03 000007",
                "T 00201A 03 000003",
                "T 00201D 03",
                "E 002000"
        ), bothPasses(Samples.ALL_COMMANDS_SAMPLE).objectCode);
    }
}
