package sysprog.shared;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sysprog.shared.AssemblerRun.firstPass;
import static sysprog.shared.AssemblerRun.program;

import java.util.ArrayList;
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
                    "PROG START 000000",
                    "000000 05 L1",
                    "000004 WORD 000028",
                    "000007 BYTE C\"Hello!\"",
                    "00000D BYTE 0C",
                    "00000E 09 B1",
                    "000012 0D B4",
                    "000016 10 R1 R2",
                    "000018 15 B1",
                    "00001C 18 C8",
                    "END 000000"
            ), firstPass(Samples.DIRECT_SAMPLE).firstPass);
        }

        @Test
        @DisplayName("Таблица символических имён")
        void symbolTable() {
            assertEquals(List.of("B1 000004", "B3 000007", "B4 00000D", "L1 00000E"),
                    firstPass(Samples.DIRECT_SAMPLE).symbolTable());
        }

        @Test
        @DisplayName("Имя программы (метка START) не попадает в ТСИ")
        void programNameIsNotASymbol() {
            assertTrue(firstPass(Samples.DIRECT_SAMPLE).symbolTable().stream().noneMatch(s -> s.startsWith("PROG ")));
        }

        @Test
        @DisplayName("Относительная адресация: те же адреса, код операции с типом 10, операнд в скобках")
        void relativeSample() {
            assertEquals(List.of(
                    "PROG START 000000",
                    "000000 06 [L1]",
                    "000004 WORD 000028",
                    "000007 BYTE C\"Hello!\"",
                    "00000D BYTE 0C",
                    "00000E 0A [B1]",
                    "000012 0E [B4]",
                    "000016 10 R1 R2",
                    "000018 16 [B1]",
                    "00001C 18 C8",
                    "END 000000"
            ), firstPass(Samples.RELATIVE_SAMPLE).firstPass);
        }
    }

    @Nested
    @DisplayName("Начальный адрес (перемещаемый формат)")
    class StartAddress {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"P START", "P START 0", "P START 000000"})
        @DisplayName("Программа ассемблируется с адреса 0: START без операнда или с нулём")
        void startsAtZero(String startLine) {
            AssemblerRun run = firstPass(program(startLine, "L1 RET", " END"));

            assertEquals(List.of("P START 000000", "000000 28", "END 000000"), run.firstPass);
            assertEquals(List.of("L1 000000"), run.symbolTable());
        }

        @ParameterizedTest(name = "START {0}")
        @ValueSource(strings = {"100", "1", "G00", "1000000"})
        @DisplayName("Ненулевой или некорректный операнд START — ошибка")
        void rejectsNonZeroStart(String operand) {
            assertError(program("P START " + operand, " RET", " END"), "операнд START должен отсутствовать или быть равен 0");
        }

        @Test
        @DisplayName("START с двумя операндами — ошибка")
        void rejectsTwoOperands() {
            assertError(program("P START 0 0", " RET", " END"), "Неверное количество операндов для START");
        }
    }

    @Nested
    @DisplayName("Структура программы")
    class Structure {

        @Test
        @DisplayName("Программа должна начинаться с START")
        void requiresStartFirst() {
            assertError(program(" RET", "P START", " END"), "должна начинаться с директивы START");
        }

        @Test
        @DisplayName("START без метки (имени программы) — ошибка")
        void requiresProgramName() {
            error(program(" START", " RET", " END"));
        }

        @Test
        @DisplayName("Повторный START — ошибка")
        void rejectsSecondStart() {
            assertError(program("P START", "Q START 200", " END"), "START должен быть единственным");
        }

        @Test
        @DisplayName("Нет END — ошибка")
        void requiresEnd() {
            assertError(program("P START", " RET"), "Не найдена директива END");
        }

        @Test
        @DisplayName("Текст после END — ошибка")
        void rejectsTextAfterEnd() {
            assertError(program("P START", " RET", " END", " RET"), "Текст после директивы END");
        }

        @Test
        @DisplayName("Пустая программа — ошибка")
        void rejectsEmptyProgram() {
            assertError(program("P START", " END"), "не содержит ни одной команды");
        }

        @Test
        @DisplayName("Пустой исходный текст — ошибка")
        void rejectsEmptySource() {
            error("");
        }

        @Test
        @DisplayName("Повторная метка — ошибка")
        void rejectsDuplicateLabel() {
            assertError(program("P START", "A RET", "a RET", " END"), "Такая метка уже есть в ТСИ");
        }

        @Test
        @DisplayName("Неизвестная команда — ошибка")
        void rejectsUnknownCommand() {
            assertError(program("P START", " MOV R1 R2", " END"), "Неизвестная команда: \"MOV\"");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"1ABC RET", "ABCDEFGHIJK RET", "R1 RET", "A-B RET", "A B C D E"})
        @DisplayName("Неверная метка или формат строки — ошибка")
        void rejectsMalformedLine(String badLine) {
            error(program("P START", badLine, " END"));
        }

        @Test
        @DisplayName("Метка с именем регистра — конкретная ошибка, а не общий формат")
        void rejectsLabelNamedAsRegister() {
            assertError(program("P START", "R1 RET", " END"), "Имя метки не должно совпадать с именем регистра: \"R1\"");
        }

        @Test
        @DisplayName("Метка с именем команды перед командой с двумя операндами — конкретная ошибка")
        void rejectsLabelNamedAsCommand() {
            assertError(program("P START", "RET JMP B1 C1", " END"), "Имя метки не должно совпадать с именем команды: \"RET\"");
        }

        @Test
        @DisplayName("Метка с именем директивы перед командой с двумя операндами — конкретная ошибка")
        void rejectsLabelNamedAsDirective() {
            assertError(program("P START", "WORD JMP B1 C1", " END"), "Имя метки не должно совпадать с именем директивы: \"WORD\"");
        }

        @Test
        @DisplayName("Метка длиннее 10 символов — конкретная ошибка")
        void rejectsTooLongLabel() {
            assertError(program("P START", "ABCDEFGHIJK RET", " END"), "Длина метки не должна превышать 10 символов");
        }

        @Test
        @DisplayName("Слишком много слов в строке — конкретная ошибка")
        void rejectsTooManyWords() {
            assertError(program("P START", "A B C D E", " END"), "Слишком много слов в строке");
        }

        @Test
        @DisplayName("Цифры, приклеенные к команде, — неизвестная команда с подсказкой")
        void digitsGluedToCommand() {
            assertError(program("P START", " 000JMP L1", " END"),
                    "Неизвестная команда: \"000JMP\" — имя команды должно начинаться с латинской буквы");
            assertError(program("P START", " 000JMP L1", " END"), "Лишние символы \"000\" перед JMP");
        }

        @Test
        @DisplayName("Цифры, приклеенные к команде после метки, — ошибка указывает на команду, а не на метку")
        void digitsGluedToCommandAfterLabel() {
            assertError(program("P START", "L2 000JMP L1", " END"), "Неизвестная команда: \"000JMP\"");
        }

        @Test
        @DisplayName("Число вместо метки перед командой — некорректная метка")
        void numberInsteadOfLabel() {
            assertError(program("P START", " 000 JMP L1", " END"),
                    "Некорректная метка \"000\": метка должна начинаться с латинской буквы");
        }

        @Test
        @DisplayName("Число между меткой и командой — лишнее слово")
        void numberBetweenLabelAndCommand() {
            assertError(program("P START", "L2 000 JMP L1", " END"), "Лишнее слово \"000\" между меткой и командой JMP");
        }

        @Test
        @DisplayName("Директива без операнда после метки — неверное количество операндов")
        void directiveWithoutOperandAfterLabel() {
            assertError(program("P START", "A WORD", " END"), "Неверное количество операндов для WORD");
        }

        @Test
        @DisplayName("Метки, команды и регистры не зависят от регистра букв")
        void isCaseInsensitive() {
            AssemblerRun run = firstPass(program("p start", "loop add r1 r2", " jmp LOOP", " end"));

            assertEquals(List.of("P START 000000", "000000 10 R1 R2", "000002 05 LOOP", "END 000000"), run.firstPass);
            assertEquals(List.of("LOOP 000000"), run.symbolTable());
        }
    }

    @Nested
    @DisplayName("Директивы данных")
    class DataDirectives {

        @Test
        @DisplayName("WORD — 3 байта, значение в 16-ричном виде")
        void word() {
            AssemblerRun run = firstPass(program("P START", "A WORD 4096", "B WORD 0", "C WORD 16777215", "D RET", " END"));

            assertEquals(List.of("P START 000000", "000000 WORD 001000", "000003 WORD 000000", "000006 WORD FFFFFF", "000009 28", "END 000000"), run.firstPass);
        }

        @Test
        @DisplayName("Ведущие нули в десятичных числах допустимы")
        void leadingZerosInDecimal() {
            assertEquals("000000 WORD 000028", line(program("P START", "A WORD 0000000040", " RET", " END"), 1));
            assertEquals("000000 18 C8", line(program("P START", " INT 0000200", " END"), 1));
        }

        @ParameterizedTest(name = "WORD {0}")
        @ValueSource(strings = {"16777216", "-1", "1A", "١٢"})
        @DisplayName("WORD: значение вне 0..16777215 или не число — ошибка")
        void wordOutOfRange(String value) {
            error(program("P START", "A WORD " + value, " END"));
        }

        @Test
        @DisplayName("BYTE с числом — 1 байт")
        void byteNumber() {
            assertEquals(List.of("P START 000000", "000000 BYTE FF", "000001 28", "END 000000"),
                    firstPass(program("P START", "A BYTE 255", " RET", " END")).firstPass);
        }

        @Test
        @DisplayName("BYTE: число больше 255 — ошибка")
        void byteNumberOutOfRange() {
            error(program("P START", "A BYTE 256", " END"));
        }

        @Test
        @DisplayName("BYTE C\"...\" — по байту на символ")
        void byteCharacterString() {
            AssemblerRun run = firstPass(program("P START", "A BYTE C\"Hi there\"", "B RET", " END"));

            assertEquals(List.of("A 000000", "B 000008"), run.symbolTable());
        }

        @Test
        @DisplayName("BYTE C\"...\" с кавычками внутри — кавычки считаются символами строки")
        void byteCharacterStringWithQuotes() {
            AssemblerRun run = firstPass(program("P START", "A BYTE C\"\"Hello!\"\"", "B BYTE C\"He\"llo\"", "C RET", " END"));

            assertEquals("000000 BYTE C\"\"Hello!\"\"", run.firstPass.get(1));
            assertEquals(List.of("A 000000", "B 000008", "C 00000E"), run.symbolTable());
        }

        @Test
        @DisplayName("BYTE X\"...\" — по байту на две 16-ричные цифры")
        void byteHexString() {
            AssemblerRun run = firstPass(program("P START", "A BYTE X\"2F4C008A\"", "B RET", " END"));

            assertEquals("000000 BYTE X\"2F4C008A\"", run.firstPass.get(1));
            assertEquals(List.of("A 000000", "B 000004"), run.symbolTable());
        }

        @Test
        @DisplayName("Префиксы строк в нижнем регистре допустимы")
        void lowerCaseStringPrefixes() {
            AssemblerRun run = firstPass(program("P START", "A BYTE x\"2f\"", "B BYTE c\"ok\"", "C RET", " END"));

            assertEquals("000000 BYTE X\"2F\"", run.firstPass.get(1));
            assertEquals(List.of("A 000000", "B 000001", "C 000003"), run.symbolTable());
        }

        @ParameterizedTest(name = "BYTE {0}")
        @ValueSource(strings = {"X\"ABC\"", "X\"GG\"", "X\"\"", "C\"\"", "C\"Привет\"", "\"abc\"", "Hello"})
        @DisplayName("BYTE: некорректная строка — ошибка")
        void byteInvalidString(String operand) {
            error(program("P START", "A BYTE " + operand, " END"));
        }

        @Test
        @DisplayName("RESB резервирует N байт, RESW — N слов по 3 байта")
        void reserve() {
            AssemblerRun run = firstPass(program("P START", "A RESB 10", "B RESW 20", "C RET", " END"));

            assertEquals(List.of("P START 000000", "000000 RESB 0A", "00000A RESW 14", "000046 28", "END 000000"), run.firstPass);
            assertEquals(List.of("A 000000", "B 00000A", "C 000046"), run.symbolTable());
        }

        @ParameterizedTest(name = "RESB {0}")
        @ValueSource(strings = {"0", "256", "abc"})
        @DisplayName("RESB/RESW: количество вне 1..255 — ошибка")
        void reserveOutOfRange(String value) {
            error(program("P START", "A RESB " + value, " END"));
            error(program("P START", "A RESW " + value, " END"));
        }

        @Test
        @DisplayName("RESW: не больше 85 слов, чтобы длина записи (3 * N) уместилась в байт")
        void reserveWordsLimit() {
            assertEquals("000000 RESW 55", line(program("P START", "A RESW 85", " RET", " END"), 1));
            assertError(program("P START", "A RESW 86", " RET", " END"), "(1-85)");
        }

        @Test
        @DisplayName("BYTE: строка не длиннее 255 байт")
        void byteStringLimit() {
            assertDoesNotThrow(() -> firstPass(program("P START", "A BYTE C\"" + "a".repeat(255) + "\"", " RET", " END")));
            assertError(program("P START", "A BYTE C\"" + "a".repeat(256) + "\"", " RET", " END"), "не должна превышать 255 байт");
            assertError(program("P START", "A BYTE X\"" + "AB".repeat(256) + "\"", " RET", " END"), "не должна превышать 255 байт");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"A WORD", "A WORD 1 2", "A BYTE", "A RESB 1 2"})
        @DisplayName("Директива данных с неверным числом операндов — ошибка")
        void wrongOperandCount(String badLine) {
            error(program("P START", badLine, " END"));
        }
    }

    @Nested
    @DisplayName("Команды")
    class Commands {

        @Test
        @DisplayName("Команда длины 1 без операндов, тип адресации 00")
        void lengthOneCommand() {
            assertEquals("000000 28", line(program("P START", " RET", " END"), 1));
        }

        @Test
        @DisplayName("Команда длины 1 с операндом — ошибка")
        void lengthOneCommandWithOperand() {
            error(program("P START", " RET 5", " END"));
        }

        @Test
        @DisplayName("Команда длины 2 с двумя регистрами")
        void lengthTwoRegisters() {
            assertEquals("000000 10 R1 R16", line(program("P START", " ADD R1 R16", " END"), 1));
        }

        @Test
        @DisplayName("Команда длины 2 с однобайтовым числом")
        void lengthTwoByteValue() {
            assertEquals("000000 18 FF", line(program("P START", " INT 255", " END"), 1));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {" ADD R1 R17", " ADD R0 R1", " ADD R1 B", " ADD", " INT 256", " INT X"})
        @DisplayName("Команда длины 2 с неверными операндами — ошибка")
        void lengthTwoInvalid(String badLine) {
            error(program("P START", badLine, " END"));
        }

        @Test
        @DisplayName("Команда длины 4 с меткой — тип адресации 01")
        void lengthFourLabel() {
            assertEquals("000000 05 L", line(program("P START", "L JMP L", " END"), 1));
        }

        @Test
        @DisplayName("Команда длины 4 с числом — тип адресации 00")
        void lengthFourNumber() {
            assertEquals("000000 04 000010", line(program("P START", " JMP 16", " END"), 1));
        }

        @Test
        @DisplayName("Ссылка вперёд на метку разрешается (метка ещё не определена в первом проходе)")
        void forwardReference() {
            assertDoesNotThrow(() -> firstPass(program("P START", " JMP LATER", "LATER RET", " END")));
        }

        @Test
        @DisplayName("Операнд, похожий на регистр/команду/директиву, трактуется как метка — первый проход проходит")
        void operandResemblingReservedWordIsTreatedAsLabel() {
            assertDoesNotThrow(() -> firstPass(program("P START", " JMP R1", " END")));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {" JMP", " JMP A B", " JMP 16777216", " JMP 1ABC"})
        @DisplayName("Команда длины 4 с неверными операндами — ошибка")
        void lengthFourInvalid(String badLine) {
            error(program("P START", badLine, " END"));
        }

        @Test
        @DisplayName("Команда длины 4 с [меткой] — тип адресации 10")
        void lengthFourRelative() {
            assertEquals("000000 06 [L]", line(program("P START", "L JMP [L]", " END"), 1));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {" JMP [16]", " JMP [1ABC]", " JMP [A", " JMP []", " JMP [[A]]"})
        @DisplayName("В скобках относительной адресации не метка — ошибка")
        void relativeInvalid(String badLine) {
            error(program("P START", badLine, " END"));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {" ADD [A]", " INT [A]", " RET [A]"})
        @DisplayName("Относительная адресация у команд длины 1 и 2 — ошибка")
        void relativeInShortCommand(String badLine) {
            error(program("P START", badLine, "A RET", " END"));
        }

        @Test
        @DisplayName("Пропущена команда перед [меткой] — ошибка указывает на неизвестную команду, а не на операнд")
        void missingCommandBeforeRelativeOperand() {
            assertError(program("P START", "L1 [L1]", " END"), "Неизвестная команда: \"L1\"");
        }
    }

    @Nested
    @DisplayName("Точка входа (END)")
    class EntryPoint {

        @Test
        @DisplayName("END без операнда — вход в начало программы")
        void defaultsToStart() {
            AssemblerRun run = firstPass(program("P START", " RET", " END")).secondPass();

            assertEquals("E 000000", run.objectCode.get(run.objectCode.size() - 1));
        }

        @Test
        @DisplayName("END с меткой")
        void label() {
            AssemblerRun run = firstPass(program("P START", " RET", "GO RET", " END GO")).secondPass();

            assertEquals("E 000001", run.objectCode.get(run.objectCode.size() - 1));
        }

        @Test
        @DisplayName("END с 16-ричным адресом")
        void hexAddress() {
            AssemblerRun run = firstPass(program("P START", " RET", " RET", " END 1")).secondPass();

            assertEquals("E 000001", run.objectCode.get(run.objectCode.size() - 1));
        }

        @Test
        @DisplayName("END с 16-ричным адресом, похожим на метку, которой нет в ТСИ")
        void hexAddressLookingLikeLabel() {
            AssemblerRun run = firstPass(program("P START", "A RESB 16", " END B")).secondPass();

            assertEquals("E 00000B", run.objectCode.get(run.objectCode.size() - 1));
        }

        @Test
        @DisplayName("Метка в END важнее совпадающего 16-ричного адреса")
        void labelWinsOverHexAddress() {
            AssemblerRun run = firstPass(program("P START", " RET", "A RESB 16", " END A")).secondPass();

            assertEquals("E 000001", run.objectCode.get(run.objectCode.size() - 1));
        }

        @Test
        @DisplayName("Неизвестная метка в END — ошибка")
        void unknownLabel() {
            assertError(program("P START", " RET", " END NOWHERE"), "Метка точки входа не найдена");
        }

        @ParameterizedTest(name = "END {0}")
        @ValueSource(strings = {"1", "FF", "ZZ9!"})
        @DisplayName("Адрес входа вне программы или некорректный — ошибка")
        void outsideProgram(String operand) {
            error(program("P START", " RET", " END " + operand));
        }

        @Test
        @DisplayName("Метка перед END — ошибка: она указывала бы за конец программы")
        void labelBeforeEnd() {
            assertError(program("P START", " RET", "X END"), "Перед директивой END не должно быть метки");
        }
    }

    @Nested
    @DisplayName("Границы памяти (0..FFFFFF)")
    class Memory {

        private static String[] filledUpTo(int size, String... tail) {
            List<String> lines = new ArrayList<>(List.of("P START"));
            for (int left = size; left > 0; left -= 255)
                lines.add(" RESB " + Math.min(left, 255));
            lines.addAll(List.of(tail));
            return lines.toArray(new String[0]);
        }

        @Test
        @DisplayName("Программа может занять последний байт памяти")
        void fitsUpToLastByte() {
            List<String> code = firstPass(program(filledUpTo(0xFFFFFE, " INT 1", " END"))).firstPass;

            assertEquals("FFFFFE 18 01", code.get(code.size() - 2));
        }

        @Test
        @DisplayName("Выход за последний байт — ошибка переполнения")
        void overflowsPastLastByte() {
            assertError(program(filledUpTo(0xFFFFFF, " INT 1", " END")), "переполнение");
        }

        @Test
        @DisplayName("Переполнение при резервировании памяти")
        void overflowOnReserve() {
            assertError(program(filledUpTo(0xFFFF00, "A RESB 255", "B RESW 1", " END")), "переполнение");
        }
    }

    @Test
    @DisplayName("Повторный запуск не накапливает старые данные")
    void runsAreIndependent() {
        Assembler assembler = new Assembler();
        String source = program("P START", "A RET", " END");

        assembler.firstPass(Parser.parseCode(source));
        assembler.firstPass(Parser.parseCode(source));

        assertEquals(1, assembler.getSymbolTable().size());
    }
}
