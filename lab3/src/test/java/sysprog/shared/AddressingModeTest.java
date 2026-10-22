package sysprog.shared;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sysprog.shared.AssemblerRun.program;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sysprog.app.Samples;
import sysprog.shared.exceptions.AssemblerException;
import sysprog.shared.helpers.Parser;

@DisplayName("Режим адресации")
class AddressingModeTest {

    private static List<String> bothPasses(AddressingMode mode, String source) {
        Assembler assembler = new Assembler();
        assembler.setAddressingMode(mode);
        List<String> intermediate = assembler.firstPass(Parser.parseCode(source));
        return assembler.secondPass(Parser.parseCode(String.join("\n", intermediate)));
    }

    private static void assertFirstPassError(AddressingMode mode, String source, String messagePart) {
        Assembler assembler = new Assembler();
        assembler.setAddressingMode(mode);

        AssemblerException error = assertThrows(AssemblerException.class,
                () -> assembler.firstPass(Parser.parseCode(source)));
        assertTrue(error.getMessage().contains(messagePart), "сообщение: " + error.getMessage());
    }

    @Test
    @DisplayName("По умолчанию включён смешанный режим")
    void mixedByDefault() {
        assertEquals(AddressingMode.MIXED, new Assembler().getAddressingMode());
    }

    @Test
    @DisplayName("Смешанный режим принимает все примеры с адресацией")
    void mixedAcceptsAll() {
        for (String sample : List.of(Samples.DIRECT_SAMPLE, Samples.RELATIVE_SAMPLE, Samples.MIXED_SAMPLE))
            assertDoesNotThrow(() -> bothPasses(AddressingMode.MIXED, sample));
    }

    @Test
    @DisplayName("Режим «только прямая» ассемблирует пример с прямой адресацией")
    void directOnlyAcceptsDirect() {
        assertDoesNotThrow(() -> bothPasses(AddressingMode.DIRECT_ONLY, Samples.DIRECT_SAMPLE));
    }

    @Test
    @DisplayName("Режим «только относительная» ассемблирует пример с относительной адресацией")
    void relativeOnlyAcceptsRelative() {
        assertDoesNotThrow(() -> bothPasses(AddressingMode.RELATIVE_ONLY, Samples.RELATIVE_SAMPLE));
    }

    @Test
    @DisplayName("Режим «только прямая» запрещает операнд [метка] с понятным сообщением")
    void directOnlyRejectsRelative() {
        assertFirstPassError(AddressingMode.DIRECT_ONLY, program("P START", "L JMP [L]", " END"),
                "Относительная адресация [метка] запрещена в режиме «Только прямая адресация»");
    }

    @Test
    @DisplayName("Режим «только относительная» запрещает операнд-метку без скобок с понятным сообщением")
    void relativeOnlyRejectsDirect() {
        assertFirstPassError(AddressingMode.RELATIVE_ONLY, program("P START", "L JMP L", " END"),
                "Прямая адресация запрещена в режиме «Только относительная адресация»");
    }

    @Test
    @DisplayName("Смешанный пример не проходит ни в одном из строгих режимов")
    void mixedSampleRejectedByStrictModes() {
        assertFirstPassError(AddressingMode.DIRECT_ONLY, Samples.MIXED_SAMPLE, "Относительная адресация");
        assertFirstPassError(AddressingMode.RELATIVE_ONLY, Samples.MIXED_SAMPLE, "Прямая адресация");
    }

    @Test
    @DisplayName("Числовой операнд и регистры разрешены в любом строгом режиме")
    void valueAddressingAlwaysAllowed() {
        String source = program("P START", " JMP 16", " ADD R1 R2", " INT 5", " END");
        assertDoesNotThrow(() -> bothPasses(AddressingMode.DIRECT_ONLY, source));
        assertDoesNotThrow(() -> bothPasses(AddressingMode.RELATIVE_ONLY, source));
    }

    @Test
    @DisplayName("Второй проход тоже проверяет режим, если его сменили после первого")
    void secondPassChecksMode() {
        Assembler assembler = new Assembler();
        List<String> intermediate = assembler.firstPass(Parser.parseCode(program("P START", "L JMP L", " END")));
        assembler.setAddressingMode(AddressingMode.RELATIVE_ONLY);

        AssemblerException error = assertThrows(AssemblerException.class,
                () -> assembler.secondPass(Parser.parseCode(String.join("\n", intermediate))));
        assertTrue(error.getMessage().contains("Прямая адресация запрещена"), "сообщение: " + error.getMessage());
    }
}
