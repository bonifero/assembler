package sysprog.shared;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sysprog.app.Samples;
import sysprog.shared.exceptions.AssemblerException;
import sysprog.shared.helpers.*;
import sysprog.shared.structure.*;

class SectionsAndExternalsTest {
    private static Assembler prepare(String source) {
        Assembler a = new Assembler();
        a.reset(Parser.parseCode(source));
        return a;
    }
    private static void steps(Assembler a, int count) {
        for (int i = 0; i < count; i++) a.step();
    }

    @Test void separateModulesMatchLab3() {
        Assembler a = prepare(Samples.CONTROL_SECTION_SAMPLE);
        assertEquals(List.of("H MAIN 000000 000005", "R PRINT",
                "T 000000 04 25000000", "T 000004 01 28", "M 000000 PRINT", "E 000000",
                "H LIB 000000 000003", "D PRINT 000000", "T 000000 02 1801",
                "T 000002 01 28", "E 000000"), a.run());
        assertEquals(List.of(new Section("MAIN", 5, 0), new Section("LIB", 3, 0)), a.getSections());
        assertEquals(List.of(new RelocationEntry(0, "PRINT", "MAIN")), a.getRelocationTable());
        assertEquals(List.of("MAIN"), a.getExternalReferenceSections());
        assertTrue(a.getSymbolTable().stream().noneMatch(s -> s.section().equals("MAIN") && s.name().equals("PRINT")));
    }

    @Test void exportsAndLocalReferencesArePatchedInOneStep() {
        Assembler a = prepare("P START\n EXTDEF X\n JMP X\n JMP [X]\nX RET\n END");
        steps(a, 4);
        assertTrue(a.getObjectCode().contains("D X FFFFFF"));
        assertTrue(a.getObjectCode().contains("T 000000 04 05FFFFFF"));
        assertTrue(a.getObjectCode().contains("T 000004 04 06FFFFFF"));
        assertFalse(a.getExternalNames().get(0).isDefined());
        assertEquals(List.of(0, 4), a.getSymbolTable().get(0).pendingReferences());
        a.step();
        assertTrue(a.getObjectCode().contains("D X 000008"));
        assertTrue(a.getObjectCode().contains("T 000000 04 05000008"));
        assertTrue(a.getObjectCode().contains("T 000004 04 06000000"));
        assertTrue(a.getSymbolTable().get(0).pendingReferences().isEmpty());
        assertTrue(a.getSymbolTable().get(0).external());
        assertFalse(a.isFinished());
    }

    @Test void sameLocalNameAndRecordIndexInDifferentSections() {
        Assembler a = prepare("P START\n JMP [X]\nX RET\nQ CSECT\n JMP [X]\n RET\nX RET\n END");
        steps(a, 4);
        var firstModule = a.getObjectCode().subList(0, 4);
        assertEquals(List.of("H P 000000 000005", "T 000000 04 06000000", "T 000004 01 28", "E 000000"), firstModule);
        assertEquals("Q", a.getCurrentSection());
        steps(a, 2);
        assertTrue(a.getObjectCode().contains("T 000000 04 06FFFFFF"));
        a.step();
        assertTrue(a.getObjectCode().contains("T 000000 04 06000001"));
        a.run();
        assertEquals(firstModule, a.getObjectCode().subList(0, 4));
        assertEquals(List.of("P", "Q"), a.getSymbolTable().stream().map(SymbolicName::section).toList());
    }

    @Test void unresolvedLocalFailsAtCsectNotAtEnd() {
        Assembler a = prepare("P START\n JMP X\nQ CSECT\nX RET\n END");
        steps(a, 2);
        assertTrue(assertThrows(AssemblerException.class, a::step).getMessage().contains("неопределёнными"));
        assertEquals(2, a.getNextLine());
        assertEquals("P", a.getCurrentSection());
        assertTrue(a.getSections().isEmpty());
    }

    @Test void csectOperandBelongsToPreviousSection() {
        Assembler a = prepare("P START\n RET\nENTRY RET\nQ CSECT ENTRY\n RET\nLAST RET\n END LAST");
        a.run();
        assertEquals(List.of(new Section("P", 2, 1), new Section("Q", 2, 1)), a.getSections());
    }

    @Test void externalReferenceIsNotResolvedByOtherSection() {
        Assembler a = prepare("P START\n EXTREF X\n CALL X\nQ CSECT\n EXTDEF X\n RET\nX RET\n END");
        a.run();
        assertTrue(a.getObjectCode().contains("T 000000 04 25000000"));
        assertTrue(a.getObjectCode().contains("D X 000001"));
        assertTrue(a.getObjectCode().contains("M 000000 X"));
    }

    @Test void unusedImportsNeedNoDefinitionInThisFile() {
        Assembler a = prepare("P START\n EXTREF X Y\n RET\n END");
        assertEquals(List.of("H P 000000 000001", "R X", "R Y", "T 000000 01 28", "E 000000"), a.run());
        assertTrue(a.getRelocationTable().isEmpty());
    }

    @Test void exportCanUseRelativeAddressingButImportCannot() {
        Assembler a = prepare(Samples.EXTERNAL_RELATIVE_SAMPLE);
        a.setAddressingMode(AddressingMode.RELATIVE_ONLY);
        a.run();
        assertTrue(a.getRelocationTable().isEmpty());
        assertTrue(a.getObjectCode().contains("D STR1 000012"));
        a.reset(Parser.parseCode("P START\n EXTREF X\n CALL X\n END"));
        assertTrue(assertThrows(AssemblerException.class, a::run).getMessage().contains("только прямо"));
        a.reset(Parser.parseCode("P START\n EXTREF X\n CALL [X]\n END"));
        a.setAddressingMode(AddressingMode.MIXED);
        assertTrue(assertThrows(AssemblerException.class, a::run).getMessage().contains("нельзя адресовать относительно"));
    }

    @Test void declarationsAreCaseInsensitiveAndAccumulate() {
        Assembler a = prepare("P START\n extdef a\n EXTDEF b\n extref x y\na JMP x\nb CALL Y\n END");
        a.run();
        assertEquals(List.of("A", "B"), a.getExternalNames().stream().map(SymbolicName::name).toList());
        assertEquals(List.of("X", "Y"), a.getExternalReferences());
        assertEquals(List.of(new RelocationEntry(0, "X", "P"), new RelocationEntry(4, "Y", "P")), a.getRelocationTable());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "P START\n EXTDEF X\n RET\n END",
        "P START\n EXTDEF X X\nX RET\n END",
        "P START\n EXTREF X\n EXTREF x\n RET\n END",
        "P START\n EXTDEF X\n EXTREF X\nX RET\n END",
        "P START\n EXTREF X\nX RET\n END",
        "P START\n EXTREF X\n RET\n END X",
        "P START\n EXTREF X\n RET\nQ CSECT X\n RET\n END",
        "P START\nL EXTDEF X\nX RET\n END",
        "P START\nL EXTREF X Y\n RET\n END",
        "P START\n EXTREF\n RET\n END",
        "P START\n EXTDEF X Y Z\n RET\n END",
        "P START\n EXTDEF 1X\n RET\n END",
        "P START\n EXTREF R1\n RET\n END",
        "P START\n EXTREF JMP\n RET\n END",
        "P START\n EXTREF VERYLONGNAME\n RET\n END",
        "P START\n RET\n EXTDEF X\nX RET\n END",
        "P START\n RET\nQ CSECT\n RET\n EXTREF X\n END",
        "P START\n RET\n CSECT\n RET\n END",
        "P START\n RET\nP CSECT\n RET\n END",
        "P START\n RET\nQ CSECT\n END",
        "P START\n EXTDEF X\nX RET\nQ CSECT\n EXTDEF X\nX RET\n END",
        "P START\nX RET\nQ CSECT\n JMP X\n END",
        "P START\n RET\nQ CSECT 1\n RET\n END"
    })
    void invalidDeclarationsAndSections(String source) {
        assertThrows(AssemblerException.class, () -> prepare(source).run());
    }

    @Test void invalidLabelledDataDoesNotResolvePendingReferences() {
        Assembler a = prepare("P START\n JMP X\nX WORD abc\n END");
        steps(a, 2);
        var before = a.getObjectCode();
        assertThrows(AssemblerException.class, a::step);
        assertEquals(before, a.getObjectCode());
        assertFalse(a.getSymbolTable().get(0).isDefined());
    }

    @Test void resetClearsAllSectionState() {
        Assembler a = prepare(Samples.CONTROL_SECTION_SAMPLE);
        a.run();
        a.reset(Parser.parseCode("MAIN START\n EXTDEF PRINT\nPRINT RET\n END"));
        assertTrue(a.getSections().isEmpty());
        assertTrue(a.getExternalNames().isEmpty());
        assertTrue(a.getExternalReferences().isEmpty());
        assertTrue(a.getExternalReferenceSections().isEmpty());
        assertTrue(a.getRelocationTable().isEmpty());
        assertTrue(a.getSymbolTable().isEmpty());
        assertTrue(a.getObjectCode().isEmpty());
        assertDoesNotThrow(a::run);
    }

    @Test void saveFullModule(@TempDir Path dir) throws Exception {
        var code = prepare(Samples.CONTROL_SECTION_SAMPLE).run();
        Path path = ObjectModuleFile.withExtension(dir.resolve("sections"));
        assertEquals("sections.txt", path.getFileName().toString());
        ObjectModuleFile.save(path, code);
        assertEquals(code, Files.readAllLines(path));
        assertThrows(AssemblerException.class, () -> ObjectModuleFile.save(path, List.of()));
    }
}
