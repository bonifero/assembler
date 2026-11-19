package sysprog.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sysprog.shared.dto.CommandDto;
import sysprog.shared.exceptions.AssemblerException;
import sysprog.shared.helpers.Parser;

@DisplayName("Разбор исходного текста и ТКО")
class ParserTest {

    @Test
    @DisplayName("Строка разбивается на слова по пробелам и табуляциям")
    void splitsWordsBySpacesAndTabs() {
        assertEquals(List.of(List.of("L1", "LOADR1", "B1")), Parser.parseCode("L1\tLOADR1   B1"));
    }

    @Test
    @DisplayName("Пустые строки пропускаются, поддерживаются переводы строк \\n и \\r\\n")
    void skipsEmptyLines() {
        assertEquals(List.of(List.of("A"), List.of("B")), Parser.parseCode("A\r\n\n   \nB\n"));
    }

    @Test
    @DisplayName("Символьная строка с пробелами остаётся одним словом")
    void keepsCharacterStringWithSpacesAsOneWord() {
        assertEquals(List.of(List.of("B3", "BYTE", "C\"Hello world\"")), Parser.parseCode("B3 BYTE C\"Hello world\""));
    }

    @Test
    @DisplayName("Строки с префиксом в нижнем регистре тоже распознаются")
    void recognisesLowerCaseStringPrefix() {
        assertEquals(List.of(List.of("BYTE", "c\"a b\"")), Parser.parseCode("BYTE c\"a b\""));
    }

    @Test
    @DisplayName("ТКО: каждая строка превращается в имя, код и длину")
    void parsesCommandTable() {
        List<CommandDto> commands = Parser.parseCommandTable("JMP 01 4\nADD 04 2");

        assertEquals(2, commands.size());
        assertEquals("JMP", commands.get(0).name());
        assertEquals("01", commands.get(0).code());
        assertEquals("4", commands.get(0).length());
    }

    @Test
    @DisplayName("ТКО: строка не из трёх полей — ошибка")
    void rejectsCommandTableLineWithWrongFieldCount() {
        assertThrows(AssemblerException.class, () -> Parser.parseCommandTable("JMP 01"));
        assertThrows(AssemblerException.class, () -> Parser.parseCommandTable("JMP 01 4 5"));
    }
}
