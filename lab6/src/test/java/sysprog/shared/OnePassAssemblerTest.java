package sysprog.shared;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import sysprog.app.Samples;
import sysprog.shared.helpers.Parser;
import sysprog.shared.exceptions.AssemblerException;

class OnePassAssemblerTest {
    private static Assembler prepare(String source) {
        Assembler a = new Assembler();
        a.reset(Parser.parseCode(source));
        return a;
    }

    private static List<String> withoutModifications(Assembler a) {
        return a.getObjectCode().stream().filter(r -> !r.startsWith("M ")).toList();
    }

    @Test void directSampleMatchesLab3Format() {
        Assembler a = prepare(Samples.DIRECT_SAMPLE);
        assertEquals(List.of("H PROG 000000 00001E", "M 000000", "M 00000E", "M 000012", "M 000018", "T 000000 04 0500000E",
                "T 000004 03 000028", "T 000007 06 48656C6C6F21", "T 00000D 01 0C",
                "T 00000E 04 09000004", "T 000012 04 0D00000D", "T 000016 02 1001",
                "T 000018 04 15000004", "T 00001C 02 18C8", "E 000000"), a.run());
        assertEquals(List.of(0, 14, 18, 24), a.getRelocationTable().stream().map(e -> e.address()).toList());
    }

    @Test void mixedForwardReferencesPatchedOnDefinition() {
        Assembler a = prepare("P START\n JMP L\n JMP [L]\n LOADR1 [L]\nL RET\n JMP [L]\n END L");
        for (int i = 0; i < 4; i++) a.step();
        assertEquals(List.of(0, 4, 8), a.getSymbolTable().get(0).pendingReferences());
        assertEquals("T 000004 04 06FFFFFF", withoutModifications(a).get(2));
        assertEquals(List.of(0), a.getRelocationTable().stream().map(e -> e.address()).toList());
        a.step();
        assertEquals("T 000000 04 0500000C", withoutModifications(a).get(1));
        assertEquals("T 000004 04 06000004", withoutModifications(a).get(2));
        assertEquals("T 000008 04 0A000000", withoutModifications(a).get(3));
        assertTrue(a.getSymbolTable().get(0).pendingReferences().isEmpty());
        a.step();
        assertEquals("T 00000D 04 06FFFFFB", withoutModifications(a).get(5));
        assertFalse(a.isFinished());
        assertTrue(a.step());
        assertEquals("H P 000000 000011", withoutModifications(a).get(0));
        assertEquals("E 00000C", withoutModifications(a).get(6));
        var code = a.getObjectCode();
        assertTrue(a.step());
        assertEquals(code, a.getObjectCode());
    }

    @Test void modesAndImmediateOperands() {
        assertEquals(AddressingMode.MIXED, new Assembler().getAddressingMode());
        for (AddressingMode mode : AddressingMode.values()) {
            Assembler a = prepare("P START 0\n JMP 16\n ADD R1 R2\n INT 5\n END");
            a.setAddressingMode(mode);
            assertDoesNotThrow(a::run);
            assertTrue(a.getRelocationTable().isEmpty());
        }
        Assembler direct = prepare(Samples.DIRECT_SAMPLE);
        direct.setAddressingMode(AddressingMode.DIRECT_ONLY);
        assertDoesNotThrow(direct::run);
        Assembler relative = prepare(Samples.RELATIVE_SAMPLE);
        relative.setAddressingMode(AddressingMode.RELATIVE_ONLY);
        relative.run();
        assertTrue(relative.getRelocationTable().isEmpty());
        assertEquals("T 000000 04 0600000A", relative.getObjectCode().get(1));
        assertEquals("T 00000E 04 0AFFFFF2", relative.getObjectCode().get(5));
        for (AddressingMode mode : List.of(AddressingMode.DIRECT_ONLY, AddressingMode.RELATIVE_ONLY)) {
            Assembler a = prepare(Samples.MIXED_SAMPLE);
            a.setAddressingMode(mode);
            assertTrue(assertThrows(AssemblerException.class, a::run).getMessage().contains("запрещена"));
        }
    }

    @Test void resetClearsAllStateAndAllowsModeChange() {
        Assembler a = prepare("P START\n JMP L\n END");
        a.step(); a.step();
        assertThrows(AssemblerException.class, () -> a.setAddressingMode(AddressingMode.RELATIVE_ONLY));
        assertThrows(AssemblerException.class, a::run);
        assertThrows(AssemblerException.class, a::step);
        a.reset(Parser.parseCode(Samples.RELATIVE_SAMPLE));
        assertEquals(0, a.getNextLine());
        assertTrue(a.getSymbolTable().isEmpty());
        assertTrue(a.getObjectCode().isEmpty());
        assertTrue(a.getRelocationTable().isEmpty());
        a.setAddressingMode(AddressingMode.RELATIVE_ONLY);
        assertDoesNotThrow(a::run);
    }

    @Test void invalidProgramsAndOperands() {
        for (String source : List.of("", "P START 100\n RET\n END", "P START\n END",
                "P START\n JMP [MISSING]\n END", "P START\n JMP [12]\n END",
                "P START\n JMP [R1]\n END", "P START\n JMP [VERYLONGLABEL]\n END",
                "P START\n RET", "P START\nL RET\nL RET\n END",
                "P START\n RET\n END\n RET", "P START\n RET\n END 1"))
            assertThrows(AssemblerException.class, () -> prepare(source).run(), source);
    }

    @Test void samples() {
        Samples.ALL.forEach((name, source) -> {
            if (name.contains("без ошибок")) assertDoesNotThrow(() -> prepare(source).run(), name);
            else assertThrows(AssemblerException.class, () -> prepare(source).run(), name);
        });
    }

    // Large reservations exercise the signed 24-bit displacement boundaries without
    // requiring millions of source lines or a second traversal in the assembler.
    private static String reserve(int bytes) {
        return " RESB 255\n".repeat(bytes / 255) + (bytes % 255 == 0 ? "" : " RESB " + bytes % 255 + "\n");
    }

    @Test void forwardOffsetBoundaryAndOverflow() {
        Assembler valid = prepare("P START\n JMP [L]\n" + reserve(0x7FFFFF) + "L RET\n END");
        valid.run();
        assertEquals("T 000000 04 067FFFFF", valid.getObjectCode().get(1));
        Assembler invalid = prepare("P START\n JMP [L]\n" + reserve(0x800000) + "L RET\n END");
        assertTrue(assertThrows(AssemblerException.class, invalid::run).getMessage().contains("не помещается в 3 байта"));
    }

    @Test void backwardOffsetBoundaryAndOverflow() {
        Assembler valid = prepare("P START\nL RET\n" + reserve(0x800000 - 5) + " JMP [L]\n END");
        assertTrue(valid.run().contains("T 7FFFFC 04 06800000"));
        Assembler invalid = prepare("P START\nL RET\n" + reserve(0x800000 - 4) + " JMP [L]\n END");
        assertTrue(assertThrows(AssemblerException.class, invalid::run).getMessage().contains("не помещается в 3 байта"));
    }
}
