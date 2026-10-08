package sysprog.shared;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sysprog.shared.AssemblerRun.firstPass;
import static sysprog.shared.AssemblerRun.program;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import sysprog.app.Samples;
import sysprog.shared.exceptions.AssemblerException;
import sysprog.shared.helpers.Parser;

@DisplayName("Первый проход")
class FirstPassTest {

    private static AssemblerException error(String source) {
        return assertThrows(AssemblerException.class, () -> firstPass(source));
    }

    private static void assertError(String source, String messagePart) {
        String message = error(source).getMessage();
        assertTrue(message.contains(messagePart), "сообщение: " + message);
    }

    private static String line(String source, int index) {
        return firstPass(source).firstPass.get(index);
    }

    @Nested
    @DisplayName("Пример по умолчанию")
    class DefaultSample {

        @Test
        @DisplayName("Вспомогательная таблица")
        void intermediateTable() {
            assertEquals(List.of(
                    "PROG START 000100",
                    "000100 05 L1",
                    "000104 WORD 000028",
                    "000107 BYTE C\"Hello!\"",
                    "00010D BYTE 0C",
                    "00010E 09 B1",
                    "000112 0D B4",
                    "000116 10 R1 R2",
                    "000118 15 B1",
                    "00011C 18 C8",
                    "END 000100"
            ), firstPass(Samples.DEFAULT_SAMPLE).firstPass);
        }

        @Test
        @DisplayName("Таблица символических имён")
        void symbolTable() {
            assertEquals(List.of("B1 000104", "B3 000107", "B4 00010D", "L1 00010E"),
                    firstPass(Samples.DEFAULT_SAMPLE).symbolTable());
        }

        @Test
        @DisplayName("Имя программы (метка START) не попадает в ТСИ")
        void programNameIsNotASymbol() {
            assertTrue(firstPass(Samples.DEFAULT_SAMPLE).symbolTable().stream().noneMatch(s -> s.startsWith("PROG ")));
        }
    }

    @Nested
    @DisplayName("Адрес загрузки")
    class LoadAddress {

        @Test
        @DisplayName("Операнд START — 16-ричный адрес")
        void startOperandIsHex() {
            assertEquals("P START 001000", line(program("P START 1000", " RET", " END"), 0));
        }

        @Test
        @DisplayName("Ведущие нули в адресе допустимы (как в примере из задания: START 00001000)")
        void leadingZerosInAddress() {
            assertEquals("P START 001000", line(program("P START 00001000", " RET", " END"), 0));
            assertEquals("P START 001000", firstPass(program("P START 100", " RET", " END"), "00001000").firstPass.get(0));
        }

        @Test
        @DisplayName("Длинное число с ведущими нулями, но вне диапазона — ошибка диапазона")
        void longAddressOutOfRange() {
            assertError(program("P START 00000000001000000", " RET", " END"), "выходит за границы памяти");
        }

        @Test
        @DisplayName("Поле «Адрес загрузки» заменяет операнд START и сдвигает все адреса")
        void loadAddressFieldOverridesStart() {
            AssemblerRun run = firstPass(program("P START 100", "L1 RET", " END"), "ABC0");

            assertEquals(List.of("P START 00ABC0", "00ABC0 28", "END 00ABC0"), run.firstPass);
            assertEquals(List.of("L1 00ABC0"), run.symbolTable());
        }

        @Test
        @DisplayName("При заданном поле операнд START можно не писать")
        void startOperandIsOptionalWithLoadAddress() {
            assertEquals("P START 000200", firstPass(program("P START", " RET", " END"), "200").firstPass.get(0));
        }

        @Test
        @DisplayName("Пустое поле — используется операнд START")
        void blankFieldFallsBackToStart() {
            assertEquals("P START 000300", firstPass(program("P START 300", " RET", " END"), "  ").firstPass.get(0));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"XYZ", "0", "1000000", "-5"})
        @DisplayName("Неверный адрес загрузки в поле — ошибка")
        void rejectsInvalidLoadAddress(String address) {
            assertThrows(AssemblerException.class, () -> firstPass(program("P START 100", " RET", " END"), address));
        }

        @Test
        @DisplayName("Адрес не задан ни в поле, ни в START — ошибка")
        void requiresSomeLoadAddress() {
            assertError(program("P START", " RET", " END"), "Не задан адрес загрузки");
        }

        @ParameterizedTest(name = "START {0}")
        @ValueSource(strings = {"0", "G00", "1000000"})
        @DisplayName("Неверный операнд START — ошибка")
        void rejectsInvalidStartOperand(String operand) {
            error(program("P START " + operand, " RET", " END"));
        }
    }

    @Nested
    @DisplayName("Структура программы")
    class Structure {

        @Test
        @DisplayName("Программа должна начинаться с START")
        void requiresStartFirst() {
            assertError(program(" RET", "P START 100", " END"), "должна начинаться с директивы START");
        }

        @Test
        @DisplayName("START без метки (имени программы) — ошибка")
        void requiresProgramName() {
            error(program(" START 100", " RET", " END"));
        }

        @Test
        @DisplayName("Повторный START — ошибка")
        void rejectsSecondStart() {
            assertError(program("P START 100", "Q START 200", " END"), "START должен быть единственным");
        }

        @Test
        @DisplayName("Нет END — ошибка")
        void requiresEnd() {
            assertError(program("P START 100", " RET"), "Не найдена директива END");
        }

        @Test
        @DisplayName("Текст после END — ошибка")
        void rejectsTextAfterEnd() {
            assertError(program("P START 100", " RET", " END", " RET"), "Текст после директивы END");
        }

        @Test
        @DisplayName("Пустая программа — ошибка")
        void rejectsEmptyProgram() {
            assertError(program("P START 100", " END"), "не содержит ни одной команды");
        }

        @Test
        @DisplayName("Пустой исходный текст — ошибка")
        void rejectsEmptySource() {
            error("");
        }

        @Test
        @DisplayName("Повторная метка — ошибка")
        void rejectsDuplicateLabel() {
            assertError(program("P START 100", "A RET", "a RET", " END"), "Такая метка уже есть в ТСИ");
        }

        @Test
        @DisplayName("Неизвестная команда — ошибка")
        void rejectsUnknownCommand() {
            assertError(program("P START 100", " MOV R1 R2", " END"), "Неизвестная команда: \"MOV\"");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"1ABC RET", "ABCDEFGHIJK RET", "R1 RET", "A-B RET", "A B C D E"})
        @DisplayName("Неверная метка или формат строки — ошибка")
        void rejectsMalformedLine(String badLine) {
            error(program("P START 100", badLine, " END"));
        }

        @Test
        @DisplayName("Метка с именем регистра — конкретная ошибка, а не общий формат")
        void rejectsLabelNamedAsRegister() {
            assertError(program("P START 100", "R1 RET", " END"), "Имя метки не должно совпадать с именем регистра: \"R1\"");
        }

        @Test
        @DisplayName("Метка с именем команды перед командой с двумя операндами — конкретная ошибка")
        void rejectsLabelNamedAsCommand() {
            assertError(program("P START 100", "RET JMP B1 C1", " END"), "Имя метки не должно совпадать с именем команды: \"RET\"");
        }

        @Test
        @DisplayName("Метка с именем директивы перед командой с двумя операндами — конкретная ошибка")
        void rejectsLabelNamedAsDirective() {
            assertError(program("P START 100", "WORD JMP B1 C1", " END"), "Имя метки не должно совпадать с именем директивы: \"WORD\"");
        }

        @Test
        @DisplayName("Метка длиннее 10 символов — конкретная ошибка")
        void rejectsTooLongLabel() {
            assertError(program("P START 100", "ABCDEFGHIJK RET", " END"), "Длина метки не должна превышать 10 символов");
        }

        @Test
        @DisplayName("Слишком много слов в строке — конкретная ошибка")
        void rejectsTooManyWords() {
            assertError(program("P START 100", "A B C D E", " END"), "Слишком много слов в строке");
        }

        @Test
        @DisplayName("Цифры, приклеенные к команде, — неизвестная команда с подсказкой")
        void digitsGluedToCommand() {
            assertError(program("P START 100", " 000JMP L1", " END"),
                    "Неизвестная команда: \"000JMP\" — имя команды должно начинаться с латинской буквы");
            assertError(program("P START 100", " 000JMP L1", " END"), "Лишние символы \"000\" перед JMP");
        }

        @Test
        @DisplayName("Цифры, приклеенные к команде после метки, — ошибка указывает на команду, а не на метку")
        void digitsGluedToCommandAfterLabel() {
            assertError(program("P START 100", "L2 000JMP L1", " END"), "Неизвестная команда: \"000JMP\"");
        }

        @Test
        @DisplayName("Число вместо метки перед командой — некорректная метка")
        void numberInsteadOfLabel() {
            assertError(program("P START 100", " 000 JMP L1", " END"),
                    "Некорректная метка \"000\": метка должна начинаться с латинской буквы");
        }

        @Test
        @DisplayName("Число между меткой и командой — лишнее слово")
        void numberBetweenLabelAndCommand() {
            assertError(program("P START 100", "L2 000 JMP L1", " END"), "Лишнее слово \"000\" между меткой и командой JMP");
        }

        @Test
        @DisplayName("Директива без операнда после метки — неверное количество операндов")
        void directiveWithoutOperandAfterLabel() {
            assertError(program("P START 100", "A WORD", " END"), "Неверное количество операндов для WORD");
        }

        @Test
        @DisplayName("Метки, команды и регистры не зависят от регистра букв")
        void isCaseInsensitive() {
            AssemblerRun run = firstPass(program("p start 100", "loop add r1 r2", " jmp LOOP", " end"));

            assertEquals(List.of("P START 000100", "000100 10 R1 R2", "000102 05 LOOP", "END 000100"), run.firstPass);
            assertEquals(List.of("LOOP 000100"), run.symbolTable());
        }
    }

    @Nested
    @DisplayName("Директивы данных")
    class DataDirectives {

        @Test
        @DisplayName("WORD — 3 байта, значение в 16-ричном виде")
        void word() {
            AssemblerRun run = firstPass(program("P START 100", "A WORD 4096", "B WORD 0", "C WORD 16777215", "D RET", " END"));

            assertEquals(List.of("P START 000100", "000100 WORD 001000", "000103 WORD 000000", "000106 WORD FFFFFF", "000109 28", "END 000100"), run.firstPass);
        }

        @Test
        @DisplayName("Ведущие нули в десятичных числах допустимы")
        void leadingZerosInDecimal() {
            assertEquals("000100 WORD 000028", line(program("P START 100", "A WORD 0000000040", " RET", " END"), 1));
            assertEquals("000100 18 C8", line(program("P START 100", " INT 0000200", " END"), 1));
        }

        @ParameterizedTest(name = "WORD {0}")
        @ValueSource(strings = {"16777216", "-1", "1A", "١٢"})
        @DisplayName("WORD: значение вне 0..16777215 или не число — ошибка")
        void wordOutOfRange(String value) {
            error(program("P START 100", "A WORD " + value, " END"));
        }

        @Test
        @DisplayName("BYTE с числом — 1 байт")
        void byteNumber() {
            assertEquals(List.of("P START 000100", "000100 BYTE FF", "000101 28", "END 000100"),
                    firstPass(program("P START 100", "A BYTE 255", " RET", " END")).firstPass);
        }

        @Test
        @DisplayName("BYTE: число больше 255 — ошибка")
        void byteNumberOutOfRange() {
            error(program("P START 100", "A BYTE 256", " END"));
        }

        @Test
        @DisplayName("BYTE C\"...\" — по байту на символ")
        void byteCharacterString() {
            AssemblerRun run = firstPass(program("P START 100", "A BYTE C\"Hi there\"", "B RET", " END"));

            assertEquals(List.of("A 000100", "B 000108"), run.symbolTable());
        }

        @Test
        @DisplayName("BYTE C\"...\" с кавычками внутри — кавычки считаются символами строки")
        void byteCharacterStringWithQuotes() {
            AssemblerRun run = firstPass(program("P START 100", "A BYTE C\"\"Hello!\"\"", "B BYTE C\"He\"llo\"", "C RET", " END"));

            assertEquals("000100 BYTE C\"\"Hello!\"\"", run.firstPass.get(1));
            assertEquals(List.of("A 000100", "B 000108", "C 00010E"), run.symbolTable());
        }

        @Test
        @DisplayName("BYTE X\"...\" — по байту на две 16-ричные цифры")
        void byteHexString() {
            AssemblerRun run = firstPass(program("P START 100", "A BYTE X\"2F4C008A\"", "B RET", " END"));

            assertEquals("000100 BYTE X\"2F4C008A\"", run.firstPass.get(1));
            assertEquals(List.of("A 000100", "B 000104"), run.symbolTable());
        }

        @Test
        @DisplayName("Префиксы строк в нижнем регистре допустимы")
        void lowerCaseStringPrefixes() {
            AssemblerRun run = firstPass(program("P START 100", "A BYTE x\"2f\"", "B BYTE c\"ok\"", "C RET", " END"));

            assertEquals("000100 BYTE X\"2F\"", run.firstPass.get(1));
            assertEquals(List.of("A 000100", "B 000101", "C 000103"), run.symbolTable());
        }

        @ParameterizedTest(name = "BYTE {0}")
        @ValueSource(strings = {"X\"ABC\"", "X\"GG\"", "X\"\"", "C\"\"", "C\"Привет\"", "\"abc\"", "Hello"})
        @DisplayName("BYTE: некорректная строка — ошибка")
        void byteInvalidString(String operand) {
            error(program("P START 100", "A BYTE " + operand, " END"));
        }

        @Test
        @DisplayName("RESB резервирует N байт, RESW — N слов по 3 байта")
        void reserve() {
            AssemblerRun run = firstPass(program("P START 100", "A RESB 10", "B RESW 20", "C RET", " END"));

            assertEquals(List.of("P START 000100", "000100 RESB 0A", "00010A RESW 14", "000146 28", "END 000100"), run.firstPass);
            assertEquals(List.of("A 000100", "B 00010A", "C 000146"), run.symbolTable());
        }

        @ParameterizedTest(name = "RESB {0}")
        @ValueSource(strings = {"0", "256", "abc"})
        @DisplayName("RESB/RESW: количество вне 1..255 — ошибка")
        void reserveOutOfRange(String value) {
            error(program("P START 100", "A RESB " + value, " END"));
            error(program("P START 100", "A RESW " + value, " END"));
        }

        @Test
        @DisplayName("RESW: не больше 85 слов, чтобы длина записи (3 * N) уместилась в байт")
        void reserveWordsLimit() {
            assertEquals("000100 RESW 55", line(program("P START 100", "A RESW 85", " RET", " END"), 1));
            assertError(program("P START 100", "A RESW 86", " RET", " END"), "(1-85)");
        }

        @Test
        @DisplayName("BYTE: строка не длиннее 255 байт")
        void byteStringLimit() {
            assertDoesNotThrow(() -> firstPass(program("P START 100", "A BYTE C\"" + "a".repeat(255) + "\"", " RET", " END")));
            assertError(program("P START 100", "A BYTE C\"" + "a".repeat(256) + "\"", " RET", " END"), "не должна превышать 255 байт");
            assertError(program("P START 100", "A BYTE X\"" + "AB".repeat(256) + "\"", " RET", " END"), "не должна превышать 255 байт");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"A WORD", "A WORD 1 2", "A BYTE", "A RESB 1 2"})
        @DisplayName("Директива данных с неверным числом операндов — ошибка")
        void wrongOperandCount(String badLine) {
            error(program("P START 100", badLine, " END"));
        }
    }

    @Nested
    @DisplayName("Команды")
    class Commands {

        @Test
        @DisplayName("Команда длины 1 без операндов, тип адресации 00")
        void lengthOneCommand() {
            assertEquals("000100 28", line(program("P START 100", " RET", " END"), 1));
        }

        @Test
        @DisplayName("Команда длины 1 с операндом — ошибка")
        void lengthOneCommandWithOperand() {
            error(program("P START 100", " RET 5", " END"));
        }

        @Test
        @DisplayName("Команда длины 2 с двумя регистрами")
        void lengthTwoRegisters() {
            assertEquals("000100 10 R1 R16", line(program("P START 100", " ADD R1 R16", " END"), 1));
        }

        @Test
        @DisplayName("Команда длины 2 с однобайтовым числом")
        void lengthTwoByteValue() {
            assertEquals("000100 18 FF", line(program("P START 100", " INT 255", " END"), 1));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {" ADD R1 R17", " ADD R0 R1", " ADD R1 B", " ADD", " INT 256", " INT X"})
        @DisplayName("Команда длины 2 с неверными операндами — ошибка")
        void lengthTwoInvalid(String badLine) {
            error(program("P START 100", badLine, " END"));
        }

        @Test
        @DisplayName("Команда длины 4 с меткой — тип адресации 01")
        void lengthFourLabel() {
            assertEquals("000100 05 L", line(program("P START 100", "L JMP L", " END"), 1));
        }

        @Test
        @DisplayName("Команда длины 4 с числом — тип адресации 00")
        void lengthFourNumber() {
            assertEquals("000100 04 000010", line(program("P START 100", " JMP 16", " END"), 1));
        }

        @Test
        @DisplayName("Ссылка вперёд на метку разрешается (метка ещё не определена в первом проходе)")
        void forwardReference() {
            assertDoesNotThrow(() -> firstPass(program("P START 100", " JMP LATER", "LATER RET", " END")));
        }

        @Test
        @DisplayName("Операнд, похожий на регистр/команду/директиву, трактуется как метка — первый проход проходит")
        void operandResemblingReservedWordIsTreatedAsLabel() {
            assertDoesNotThrow(() -> firstPass(program("P START 100", " JMP R1", " END")));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {" JMP", " JMP A B", " JMP 16777216", " JMP 1ABC"})
        @DisplayName("Команда длины 4 с неверными операндами — ошибка")
        void lengthFourInvalid(String badLine) {
            error(program("P START 100", badLine, " END"));
        }
    }

    @Nested
    @DisplayName("Точка входа (END)")
    class EntryPoint {

        @Test
        @DisplayName("END без операнда — вход в начало программы")
        void defaultsToStart() {
            AssemblerRun run = firstPass(program("P START 100", " RET", " END")).secondPass();

            assertEquals("E 000100", run.objectCode.get(run.objectCode.size() - 1));
        }

        @Test
        @DisplayName("END с меткой")
        void label() {
            AssemblerRun run = firstPass(program("P START 100", " RET", "GO RET", " END GO")).secondPass();

            assertEquals("E 000101", run.objectCode.get(run.objectCode.size() - 1));
        }

        @Test
        @DisplayName("END с 16-ричным адресом")
        void hexAddress() {
            AssemblerRun run = firstPass(program("P START 100", " RET", " RET", " END 101")).secondPass();

            assertEquals("E 000101", run.objectCode.get(run.objectCode.size() - 1));
        }

        @Test
        @DisplayName("END с 16-ричным адресом, похожим на метку, которой нет в ТСИ")
        void hexAddressLookingLikeLabel() {
            AssemblerRun run = firstPass(program("P START A0", " RET", " RET", " END A1")).secondPass();

            assertEquals("E 0000A1", run.objectCode.get(run.objectCode.size() - 1));
        }

        @Test
        @DisplayName("Метка в END важнее совпадающего 16-ричного адреса")
        void labelWinsOverHexAddress() {
            AssemblerRun run = firstPass(program("P START A0", " RET", "A0 RET", " END A0")).secondPass();

            assertEquals("E 0000A1", run.objectCode.get(run.objectCode.size() - 1));
        }

        @Test
        @DisplayName("Неизвестная метка в END — ошибка")
        void unknownLabel() {
            assertError(program("P START 100", " RET", " END NOWHERE"), "Метка точки входа не найдена");
        }

        @ParameterizedTest(name = "END {0}")
        @ValueSource(strings = {"FF", "101", "ZZ9!"})
        @DisplayName("Адрес входа вне программы или некорректный — ошибка")
        void outsideProgram(String operand) {
            error(program("P START 100", " RET", " END " + operand));
        }
    }

    @Nested
    @DisplayName("Границы памяти (0..FFFFFF)")
    class Memory {

        @Test
        @DisplayName("Программа может занять последний байт памяти")
        void fitsUpToLastByte() {
            assertEquals("FFFFFE 18 01", line(program("P START FFFFFE", " INT 1", " END"), 1));
        }

        @Test
        @DisplayName("Выход за последний байт — ошибка переполнения")
        void overflowsPastLastByte() {
            assertError(program("P START FFFFFF", " INT 1", " END"), "переполнение");
        }

        @Test
        @DisplayName("Переполнение при резервировании памяти")
        void overflowOnReserve() {
            assertError(program("P START FFFF00", "A RESB 255", "B RESW 1", " END"), "переполнение");
        }
    }

    @Test
    @DisplayName("Повторный запуск не накапливает старые данные")
    void runsAreIndependent() {
        Assembler assembler = new Assembler();
        String source = program("P START 100", "A RET", " END");

        assembler.firstPass(Parser.parseCode(source));
        assembler.firstPass(Parser.parseCode(source));

        assertEquals(1, assembler.getSymbolTable().size());
    }
}
