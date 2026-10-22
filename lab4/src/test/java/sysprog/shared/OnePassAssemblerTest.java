package sysprog.shared;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import sysprog.app.Samples;
import sysprog.shared.exceptions.AssemblerException;
import sysprog.shared.helpers.Numbers;
import sysprog.shared.helpers.Parser;

@DisplayName("Однопросмотровый ассемблер")
class OnePassAssemblerTest {

    private static Assembler prepare(String source) {
        return prepare(source, null);
    }

    private static Assembler prepare(String source, String loadAddress) {
        Assembler assembler = new Assembler();
        assembler.reset(Parser.parseCode(source), loadAddress);
        return assembler;
    }

    private static List<String> run(String source) {
        return prepare(source).run();
    }

    private static void steps(Assembler assembler, int count) {
        for (int i = 0; i < count; i++)
            assembler.step();
    }

    private static List<String> symbolTable(Assembler assembler) {
        return assembler.getSymbolTable().stream()
                .map(s -> (s.name() + " " + (s.isDefined() ? Numbers.hex(s.address(), 6) : "------") + " "
                        + s.pendingReferences().stream().map(a -> Numbers.hex(a, 6)).collect(Collectors.joining(" ")))
                        .strip())
                .toList();
    }

    private static String program(String... lines) {
        return String.join("\n", lines);
    }

    private static void assertError(String source, String messagePart) {
        AssemblerException error = assertThrows(AssemblerException.class, () -> run(source));
        assertTrue(error.getMessage().contains(messagePart), "сообщение: " + error.getMessage());
    }

    @Nested
    @DisplayName("Пример по умолчанию")
    class DefaultSample {

        @Test
        @DisplayName("Объектный модуль в абсолютном формате: заголовок, тело, точка входа")
        void objectCode() {
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
            ), run(Samples.DEFAULT_SAMPLE));
        }

        @Test
        @DisplayName("Ссылка вперёд: сначала FFFFFF и адрес команды в ТСИ, при определении метки — подстановка")
        void forwardReferenceIsPatched() {
            Assembler assembler = prepare(Samples.DEFAULT_SAMPLE);

            steps(assembler, 2);
            assertEquals(List.of("H PROG 000100", "T 000100 04 05FFFFFF"), assembler.getObjectCode());
            assertEquals(List.of("L1 ------ 000100"), symbolTable(assembler));

            steps(assembler, 3);
            assertEquals("T 000100 04 05FFFFFF", assembler.getObjectCode().get(1));

            steps(assembler, 1);
            assertEquals("T 000100 04 0500010E", assembler.getObjectCode().get(1));
            assertEquals(List.of("L1 00010E", "B1 000104", "B3 000107", "B4 00010D"), symbolTable(assembler));
        }

        @Test
        @DisplayName("Длина программы попадает в заголовок только на END")
        void headerLengthOnEnd() {
            Assembler assembler = prepare(Samples.DEFAULT_SAMPLE);
            steps(assembler, 10);
            assertEquals("H PROG 000100", assembler.getObjectCode().get(0));
            assertFalse(assembler.isFinished());

            assertTrue(assembler.step());
            assertEquals("H PROG 000100 00001E", assembler.getObjectCode().get(0));
        }
    }

    @Nested
    @DisplayName("Ссылки вперёд")
    class ForwardReferences {

        @Test
        @DisplayName("Несколько ссылок на одну метку ждут её в ТСИ и подставляются все сразу")
        void severalReferences() {
            Assembler assembler = prepare(Samples.FORWARD_REFERENCES_SAMPLE);
            steps(assembler, 8);

            assertEquals(List.of(
                    "X ------ 001000 00100C",
                    "Y ------ 001004 001016",
                    "SUBR 001014",
                    "FIN ------ 001010"
            ), symbolTable(assembler));
        }

        @Test
        @DisplayName("Итоговый код: все заглушки FFFFFF заменены адресами меток")
        void objectCode() {
            assertEquals(List.of(
                    "H PROG 001000 000023",
                    "T 001000 04 0900101D",
                    "T 001004 04 0D001020",
                    "T 001008 04 25001014",
                    "T 00100C 04 1500101D",
                    "T 001010 04 0500101B",
                    "T 001014 02 1001",
                    "T 001016 04 15001020",
                    "T 00101A 01 28",
                    "T 00101B 02 1821",
                    "T 00101D 03 000005",
                    "T 001020 03 000007",
                    "E 001000"
            ), run(Samples.FORWARD_REFERENCES_SAMPLE));
        }

        @Test
        @DisplayName("Ссылка назад сразу получает адрес")
        void backwardReference() {
            Assembler assembler = prepare(program("P START 100", "L RET", " JMP L", " END"));
            steps(assembler, 3);

            assertEquals("T 000101 04 05000100", assembler.getObjectCode().get(2));
            assertEquals(List.of("L 000100"), symbolTable(assembler));
        }

        @Test
        @DisplayName("Неопределённые метки перечисляются на END вместе с адресами ссылающихся команд")
        void undefinedLabels() {
            assertError(Samples.UNDEFINED_LABEL_SAMPLE,
                    "остались неопределёнными метки, на которые есть ссылки: L9 (ссылки из команд по адресам 000104, 000108)");
        }

        @Test
        @DisplayName("Метка точки входа должна быть определена")
        void undefinedEntryLabel() {
            assertError(program("P START 100", " JMP L", " END L"), "Метка точки входа L не определена в программе");
        }
    }

    @Nested
    @DisplayName("Абсолютный формат: START, END и адрес загрузки")
    class AbsoluteFormat {

        @Test
        @DisplayName("Адрес загрузки из поля заменяет операнд START")
        void loadAddressOverridesStart() {
            List<String> code = prepare(Samples.DEFAULT_SAMPLE, "2000").run();

            assertEquals("H PROG 002000 00001E", code.get(0));
            assertEquals("T 002000 04 0500200E", code.get(1));
            assertEquals("E 002000", code.get(code.size() - 1));
        }

        @Test
        @DisplayName("Без поля и операнда START адрес загрузки не задан")
        void noLoadAddress() {
            assertError(program("P START", " RET", " END"), "Не задан адрес загрузки");
        }

        @Test
        @DisplayName("Адрес начала не может быть нулевым ни в START, ни в поле")
        void zeroAddress() {
            assertError(program("P START 0", " RET", " END"), "Адрес начала программы не может быть равен нулю");
            assertThrows(AssemblerException.class, () -> prepare(Samples.DEFAULT_SAMPLE, "0"));
            assertThrows(AssemblerException.class, () -> prepare(Samples.DEFAULT_SAMPLE, "XYZ"));
        }

        @Test
        @DisplayName("Точка входа: метка или 16-ричный адрес внутри программы")
        void entryPoint() {
            assertEquals("E 000101", last(run(program("P START 100", " RET", "L RET", " END L"))));
            assertEquals("E 000101", last(run(program("P START 100", " RET", " RET", " END 101"))));
            assertError(program("P START 100", " RET", " END 101"), "Адрес входа в программу должен лежать в её пределах");
        }

        @Test
        @DisplayName("Метка перед END запрещена")
        void labelBeforeEnd() {
            assertError(program("P START 100", " RET", "L END"), "Перед директивой END не должно быть метки");
        }

        @Test
        @DisplayName("Относительная адресация [метка] в абсолютном формате — ошибка")
        void relativeAddressing() {
            assertError(Samples.RELATIVE_ADDRESSING_SAMPLE,
                    "Относительная адресация [метка] не поддерживается в абсолютном формате");
        }

        private static String last(List<String> code) {
            return code.get(code.size() - 1);
        }
    }

    @Nested
    @DisplayName("Пошаговое выполнение")
    class Stepping {

        @Test
        @DisplayName("Шаг обрабатывает одну строку исходника")
        void stepProcessesOneLine() {
            Assembler assembler = prepare(Samples.DEFAULT_SAMPLE);
            assertEquals(0, assembler.getNextLine());

            assembler.step();
            assertEquals(1, assembler.getNextLine());
            assertEquals(List.of("H PROG 000100"), assembler.getObjectCode());
        }

        @Test
        @DisplayName("После END шаг ничего не меняет")
        void stepAfterFinish() {
            Assembler assembler = prepare(Samples.DEFAULT_SAMPLE);
            List<String> code = assembler.run();

            assertTrue(assembler.step());
            assertEquals(code, assembler.getObjectCode());
        }

        @Test
        @DisplayName("После ошибки ассемблирование не продолжается до сброса")
        void stepAfterError() {
            Assembler assembler = prepare(Samples.UNKNOWN_COMMAND_SAMPLE);
            steps(assembler, 2);
            assertThrows(AssemblerException.class, assembler::step);

            AssemblerException again = assertThrows(AssemblerException.class, assembler::step);
            assertTrue(again.getMessage().contains("нажмите «Сброс»"));

            assembler.reset(Parser.parseCode(Samples.DEFAULT_SAMPLE));
            assertDoesNotThrow(assembler::run);
        }

        @Test
        @DisplayName("Сброс очищает ТСИ и двоичный код")
        void resetClearsState() {
            Assembler assembler = prepare(Samples.DEFAULT_SAMPLE);
            steps(assembler, 4);

            assembler.reset(Parser.parseCode(Samples.DEFAULT_SAMPLE));
            assertEquals(List.of(), assembler.getObjectCode());
            assertEquals(List.of(), assembler.getSymbolTable());
            assertEquals(0, assembler.getNextLine());
        }
    }

    @Nested
    @DisplayName("Ошибки")
    class Errors {

        @Test
        @DisplayName("Пустой исходный текст")
        void emptySource() {
            assertError("", "Исходный текст пуст");
        }

        @Test
        @DisplayName("Нет END: строки закончились")
        void noEnd() {
            assertError(Samples.NO_END_SAMPLE, "Не найдена директива END");
        }

        @Test
        @DisplayName("Текст после END обнаруживается на шаге END")
        void textAfterEnd() {
            Assembler assembler = prepare(Samples.TEXT_AFTER_END_SAMPLE);
            steps(assembler, 3);

            AssemblerException error = assertThrows(AssemblerException.class, assembler::step);
            assertEquals("Текст после директивы END: JMP L1", error.getMessage());
        }

        @Test
        @DisplayName("Повторное определение метки; ссылка вперёд на метку повтором не считается")
        void duplicateLabel() {
            assertError(Samples.DUPLICATE_LABEL_SAMPLE, "Такая метка уже есть в ТСИ: B1 BYTE 12");
        }

        @Test
        @DisplayName("Программа должна начинаться с START")
        void startFirst() {
            assertError(program(" RET", "P START 100", " END"), "Программа должна начинаться с директивы START");
        }

        @Test
        @DisplayName("Операнд команды не может быть регистром, командой или слишком длинной меткой")
        void badLabelOperands() {
            assertError(program("P START 100", " JMP R1", " END"), "Имя метки не должно совпадать с именем регистра");
            assertError(program("P START 100", " JMP JMP", " END"), "Имя метки не должно совпадать с именем команды");
            assertError(program("P START 100", " JMP VERYLONGLABEL", " END"), "Длина метки не должна превышать 10");
        }

        @Test
        @DisplayName("Лишний операнд сообщает о количестве операндов")
        void extraOperand() {
            assertError(program("P START 100", " ADD R1 R2 R3", " END"), "Неверное количество операндов для ADD");
        }

        @Test
        @DisplayName("Пустая программа между START и END")
        void emptyProgram() {
            assertError(program("P START 100", " END"), "Программа не содержит ни одной команды или данных");
        }

        @Test
        @DisplayName("Переполнение памяти")
        void overflow() {
            assertError(program("P START FFFFFE", " JMP 0", " END"), "Произошло переполнение выделенной памяти");
        }
    }

    @Nested
    @DisplayName("Примеры")
    class SamplesCheck {

        @Test
        @DisplayName("Примеры без ошибок ассемблируются, с ошибками — падают")
        void samples() {
            for (Map.Entry<String, String> sample : Samples.ALL.entrySet()) {
                if (sample.getKey().contains("без ошибок")) {
                    List<String> code = run(sample.getValue());
                    assertTrue(code.get(0).startsWith("H "), sample.getKey());
                    assertTrue(code.stream().noneMatch(r -> r.startsWith("T ") && r.contains("FFFFFF")), sample.getKey());
                } else {
                    assertThrows(AssemblerException.class, () -> run(sample.getValue()), sample.getKey());
                }
            }
        }

        @Test
        @DisplayName("Пример со всеми командами")
        void allCommands() {
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
            ), run(Samples.ALL_COMMANDS_SAMPLE));
        }
    }
}
