package sysprog.shared;

import java.util.List;
import java.util.stream.Collectors;

import sysprog.shared.helpers.Numbers;
import sysprog.shared.helpers.Parser;

final class AssemblerRun {
    final Assembler assembler = new Assembler();
    List<String> firstPass;
    List<String> objectCode;

    static AssemblerRun firstPass(String source) {
        return firstPass(source, null);
    }

    static AssemblerRun firstPass(String source, String loadAddress) {
        AssemblerRun run = new AssemblerRun();
        run.firstPass = run.assembler.firstPass(Parser.parseCode(source), loadAddress);
        return run;
    }

    static AssemblerRun bothPasses(String source) {
        return firstPass(source).secondPass();
    }

    static AssemblerRun bothPasses(String source, String loadAddress) {
        return firstPass(source, loadAddress).secondPass();
    }

    AssemblerRun secondPass() {
        objectCode = assembler.secondPass(Parser.parseCode(String.join("\n", firstPass)));
        return this;
    }

    List<String> symbolTable() {
        return assembler.getSymbolTable().stream()
                .map(s -> s.name() + " " + Numbers.hex(s.address(), 6))
                .collect(Collectors.toList());
    }

    static String program(String... lines) {
        return String.join("\n", lines);
    }
}
