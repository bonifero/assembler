package sysprog.shared;

import static sysprog.shared.helpers.Numbers.hex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import sysprog.shared.dto.CommandDto;
import sysprog.shared.exceptions.AssemblerException;
import sysprog.shared.helpers.Numbers;
import sysprog.shared.helpers.Operands;
import sysprog.shared.structure.CodeLine;
import sysprog.shared.structure.Command;
import sysprog.shared.structure.SymbolicName;
import sysprog.shared.structure.Section;
import sysprog.shared.structure.RelocationEntry;

public class Assembler {
    public static final String UNRESOLVED_OPERAND = "FFFFFF";

    private static final int MAX_ADDRESS = 0xFFFFFF;
    private static final int MAX_WORD = 0xFFFFFF;
    private static final int MAX_BYTE = 0xFF;
    private static final int MAX_RECORD_LENGTH = 0xFF;
    private static final int WORD_SIZE = 3;

    private static final int ADDRESSING_VALUE = 0;
    private static final int ADDRESSING_DIRECT = 1;
    private static final int ADDRESSING_RELATIVE = 2;

    private static final int MAX_LABEL_LENGTH = 10;
    private static final Pattern LABEL = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final List<String> DIRECTIVES = List.of("START", "END", "WORD", "BYTE", "RESB", "RESW", "CSECT", "EXTDEF", "EXTREF");

    private List<Command> commands = List.of(
            new Command("JMP", 0x01, 4),
            new Command("LOADR1", 0x02, 4),
            new Command("LOADR2", 0x03, 4),
            new Command("ADD", 0x04, 2),
            new Command("SAVER1", 0x05, 4),
            new Command("INT", 0x06, 2),
            new Command("SUB", 0x07, 2),
            new Command("MUL", 0x08, 2),
            new Command("CALL", 0x09, 4),
            new Command("RET", 0x0A, 1));

    private final Map<String, Symbol> symbolTable = new LinkedHashMap<>();
    private final List<String> objectCode = new ArrayList<>();
    private List<List<String>> source = List.of();
    private final List<RelocationEntry> relocationTable = new ArrayList<>();
    private AddressingMode addressingMode = AddressingMode.MIXED;
    private int nextLine;
    private boolean started;
    private boolean finished;
    private boolean failed;
    private String currentSection = "";
    private boolean bodyStarted;
    private final List<Section> sections = new ArrayList<>();
    private final List<String> completedCode = new ArrayList<>();
    private final List<String> declaredExternalNames = new ArrayList<>();
    private final List<String> declaredExternalReferences = new ArrayList<>();
    private final Set<String> exportedNames = new HashSet<>();
    private final List<String> externalReferences = new ArrayList<>();
    private final List<String> externalReferenceSections = new ArrayList<>();
    private int sectionRelocationsFrom;
    private int startAddress;
    private int entryAddress;
    private int locationCounter;

    private static final class Symbol {
        private final String name;
        private Integer address;
        private final String section;
        private boolean external;
        private final List<PendingReference> pendingReferences = new ArrayList<>();

        private Symbol(String name, String section) {
            this.name = name;
            this.section = section;
        }
    }

    private record PendingReference(int commandAddress, int recordIndex, int nextCommandAddress,
            int addressing, String sourceText) {
    }

    public List<RelocationEntry> getRelocationTable() {
        return List.copyOf(relocationTable);
    }

    public AddressingMode getAddressingMode() {
        return addressingMode;
    }

    public void setAddressingMode(AddressingMode mode) {
        if (started && !finished && !failed)
            throw new AssemblerException("Перед сменой режима адресации выполните сброс");
        addressingMode = java.util.Objects.requireNonNull(mode);
    }

    public List<Command> getCommands() {
        return commands;
    }

    public List<SymbolicName> getSymbolTable() {
        return symbolTable.values().stream()
                .map(s -> new SymbolicName(s.name, s.address,
                        s.pendingReferences.stream().map(PendingReference::commandAddress).toList(), s.external, s.section))
                .toList();
    }

    public List<String> getObjectCode() {
        List<String> result = new ArrayList<>(completedCode);
        result.addAll(currentSectionRecords());
        return List.copyOf(result);
    }

    // Packaging already generated records does not traverse source or encode commands again.
    private List<String> currentSectionRecords() {
        if (objectCode.isEmpty()) return List.of();
        List<String> records = new ArrayList<>();
        records.add(objectCode.get(0));
        for (String name : declaredExternalNames) {
            Symbol symbol = findSymbol(name);
            records.add("D " + name + " " + (symbol.address == null ? UNRESOLVED_OPERAND : hex(symbol.address, 6)));
        }
        for (String name : declaredExternalReferences)
            records.add("R " + name);
        boolean hasEndRecord = objectCode.get(objectCode.size() - 1).startsWith("E ");
        int textEnd = hasEndRecord ? objectCode.size() - 1 : objectCode.size();
        records.addAll(objectCode.subList(1, textEnd));
        for (RelocationEntry entry : relocationTable.subList(sectionRelocationsFrom, relocationTable.size()))
            records.add("M " + hex(entry.address(), 6)
                    + (entry.externalReference() == null ? "" : " " + entry.externalReference()));
        if (hasEndRecord)
            records.add(objectCode.get(objectCode.size() - 1));
        return records;
    }

    public List<Section> getSections() { return List.copyOf(sections); }
    public String getCurrentSection() { return currentSection; }
    public List<SymbolicName> getExternalNames() {
        return getSymbolTable().stream().filter(SymbolicName::external).toList();
    }
    public List<String> getExternalReferences() { return List.copyOf(externalReferences); }
    public List<String> getExternalReferenceSections() { return List.copyOf(externalReferenceSections); }


    public int getEntryAddress() {
        return entryAddress;
    }

    public boolean isFinished() {
        return finished;
    }

    public int getNextLine() {
        return nextLine;
    }

    public int getLineCount() {
        return source.size();
    }

    public void setCommands(List<CommandDto> table) {
        if (table.isEmpty())
            throw new AssemblerException("Таблица кодов операций пуста");

        List<Command> newCommands = table.stream().map(Command::fromDto).collect(Collectors.toList());

        requireUnique(newCommands, c -> c.name().toUpperCase(), "Все имена команд должны быть уникальными");
        if (newCommands.stream().anyMatch(c -> isDirective(c.name()) || Operands.isRegister(c.name())))
            throw new AssemblerException("Имена команд не должны совпадать с именами директив и регистров");
        requireUnique(newCommands, Command::code, "Все коды команд должны быть уникальными");

        commands = newCommands;
    }

    public void reset(List<List<String>> source) {
        this.source = List.copyOf(source);
        symbolTable.clear();
        objectCode.clear();
        relocationTable.clear();
        nextLine = 0;
        started = false;
        finished = false;
        failed = false;
        currentSection = "";
        bodyStarted = false;
        sections.clear();
        completedCode.clear();
        declaredExternalNames.clear();
        declaredExternalReferences.clear();
        exportedNames.clear();
        externalReferences.clear();
        externalReferenceSections.clear();
        sectionRelocationsFrom = 0;
        startAddress = 0;
        entryAddress = 0;
        locationCounter = 0;
    }

    public boolean step() {
        if (failed)
            throw new AssemblerException("Ассемблирование остановлено из-за ошибки: исправьте её и нажмите «Сброс»");
        if (finished)
            return true;

        try {
            processNextLine();
        } catch (AssemblerException ex) {
            failed = true;
            throw ex;
        }
        return finished;
    }

    public List<String> run() {
        while (!step()) {
        }
        return getObjectCode();
    }

    private void processNextLine() {
        if (source.isEmpty())
            throw new AssemblerException("Исходный текст пуст: программа должна начинаться с директивы START");
        if (nextLine >= source.size())
            throw new AssemblerException("Не найдена директива END (точка входа в программу).");

        List<String> words = source.get(nextLine);
        String text = String.join(" ", words);
        CodeLine line = parseSourceLine(words);

        if (!started && !line.command().equals("START"))
            throw error("Программа должна начинаться с директивы START", text);
        if (line.command().equals("END") && line.label() != null)
            throw error("Перед директивой END не должно быть метки: она указывала бы за пределы программы", text);

        boolean declaration = isExternalDirective(line.command());
        boolean boundary = line.command().equals("START") || line.command().equals("CSECT") || line.command().equals("END");
        if (declaration && bodyStarted)
            throw error("Директива " + line.command() + " должна стоять сразу после START или CSECT,"
                    + " до первой команды или директивы данных секции", text);
        boolean definesLabel = started && line.label() != null && !declaration && !boundary;
        int labelAddress = locationCounter;
        if (definesLabel)
            validateSymbolDefinition(line.label(), text);
        if (!declaration && !boundary)
            bodyStarted = true;

        switch (line.command()) {
            case "START" -> processStart(line, text);
            case "END" -> processEnd(line, text);
            case "CSECT" -> {
                validateSectionName(line.label(), text);
                finishSection(line, text);
                startSection(line.label());
            }
            case "EXTDEF", "EXTREF" -> processExternalDeclaration(line, text);
            case "WORD" -> processWord(line, text);
            case "BYTE" -> processByte(line, text);
            case "RESB", "RESW" -> processReserve(line, text);
            default -> processCommand(line, text);
        }

        if (definesLabel)
            defineSymbol(line.label(), labelAddress);
        nextLine++;
    }

    private void processStart(CodeLine line, String text) {
        if (started)
            throw error("START должен быть единственным, в начале исходного кода", text);
        if (line.secondOperand() != null)
            throw error("Ожидается один операнд, но найдено два", text);
        if (line.label() == null)
            throw error("Перед директивой START должна быть метка (имя программы)", text);

        startAddress = parseStartAddress(line.firstOperand(), text);
        validateSectionName(line.label(), text);
        startSection(line.label());
        started = true;
    }

    private static int parseStartAddress(String operand, String text) {
        if (operand != null && !Integer.valueOf(0).equals(Numbers.parseHex(operand)))
            throw error("Программа в перемещаемом формате ассемблируется с адреса 0:"
                    + " операнд START должен отсутствовать или быть равен 0", text);
        return 0;
    }

    private void validateSectionName(String name, String text) {
        if (name == null)
            throw error("Перед директивой CSECT должна быть метка (имя секции)", text);
        if (name.equals(currentSection) || sections.stream().anyMatch(section -> section.name().equals(name)))
            throw error("Имя секции " + name + " уже используется", text);
    }

    private void startSection(String name) {
        currentSection = name;
        locationCounter = 0;
        entryAddress = 0;
        bodyStarted = false;
        declaredExternalNames.clear();
        declaredExternalReferences.clear();
        sectionRelocationsFrom = relocationTable.size();
        objectCode.add("H " + name + " 000000");
    }

    private void processEnd(CodeLine line, String text) {
        if (nextLine + 1 < source.size())
            throw error("Текст после директивы END", String.join(" ", source.get(nextLine + 1)));
        finishSection(line, text);
        finished = true;
    }

    private void finishSection(CodeLine line, String text) {
        if (line.secondOperand() != null)
            throw error("Ожидается максимум один операнд (точка входа), но найдено два", text);
        if (locationCounter == 0)
            throw error("Секция " + currentSection + " не содержит ни одной команды или данных", text);
        entryAddress = resolveEntryAddress(line.firstOperand(), text);
        if (!isInsideProgram(entryAddress))
            throw error("Адрес входа в секцию " + currentSection + " должен лежать в её пределах (000000-"
                    + hex(locationCounter - 1, 6) + ")", text);
        for (String name : declaredExternalNames)
            if (findSymbol(name).address == null)
                throw error("Внешнее имя " + name + ", объявленное в EXTDEF, не определено в программе"
                        + " (секция " + currentSection + ")", text);
        requireAllSymbolsDefined(text);
        objectCode.set(0, objectCode.get(0) + " " + hex(locationCounter, 6));
        objectCode.add("E " + hex(entryAddress, 6));
        sections.add(new Section(currentSection, locationCounter, entryAddress));
        completedCode.addAll(currentSectionRecords());
        objectCode.clear();
    }

    private static boolean isExternalDirective(String word) {
        return word.equalsIgnoreCase("EXTDEF") || word.equalsIgnoreCase("EXTREF");
    }

    private void processExternalDeclaration(CodeLine line, String text) {
        if (line.label() != null)
            throw error("Перед директивой " + line.command() + " не должно быть метки", text);
        if (line.firstOperand() == null)
            throw error("Директива " + line.command() + " должна содержать одно или два имени", text);
        List<String> names = Stream.of(line.firstOperand(), line.secondOperand())
                .filter(java.util.Objects::nonNull).map(String::toUpperCase).toList();
        Set<String> seen = new HashSet<>();
        // Validate the whole declaration before changing the tables.
        for (String name : names) {
            if (!LABEL.matcher(name).matches())
                throw error("Операнд директивы " + line.command() + " должен быть именем", text);
            String problem = labelProblem(name);
            if (problem != null) throw error(problem, text);
            if (declaredExternalNames.contains(name))
                throw error("Имя " + name + " уже объявлено в EXTDEF", text);
            if (declaredExternalReferences.contains(name))
                throw error("Имя " + name + " уже объявлено в EXTREF", text);
            if (!seen.add(name))
                throw error("Имя " + name + " уже объявлено в " + line.command(), text);
            if (line.command().equals("EXTDEF") && exportedNames.contains(name))
                throw error("Внешнее имя " + name + " уже экспортируется другой секцией", text);
        }
        for (String name : names) {
            if (line.command().equals("EXTDEF")) {
                declaredExternalNames.add(name);
                exportedNames.add(name);
                Symbol symbol = new Symbol(name, currentSection);
                symbol.external = true;
                symbolTable.put(symbolKey(name), symbol);
            } else {
                declaredExternalReferences.add(name);
                externalReferences.add(name);
                externalReferenceSections.add(currentSection);
            }
        }
    }

    private int resolveEntryAddress(String operand, String text) {
        if (operand == null)
            return startAddress;

        if (declaredExternalReferences.contains(operand.toUpperCase()))
            throw error("Точкой входа не может быть внешняя ссылка " + operand.toUpperCase(), text);
        Symbol symbol = isLabel(operand) ? findSymbol(operand) : null;
        if (symbol != null && symbol.address != null)
            return symbol.address;
        if (symbol != null)
            throw error("Метка точки входа " + symbol.name + " не определена в программе", text);

        Integer address = Numbers.parseHex(operand);
        if (address != null)
            return address;

        if (isLabel(operand))
            throw error("Метка точки входа не найдена в ТСИ", text);
        throw error("Невозможно преобразовать операнд END в метку или 16-ричный адрес входа в программу", text);
    }

    private void requireAllSymbolsDefined(String text) {
        String undefined = symbolTable.values().stream()
                .filter(s -> s.section.equals(currentSection) && s.address == null)
                .map(s -> s.name + " (ссылки из команд по адресам " + s.pendingReferences.stream()
                        .map(r -> hex(r.commandAddress(), 6))
                        .collect(Collectors.joining(", ")) + ")")
                .collect(Collectors.joining("; "));

        if (!undefined.isEmpty())
            throw error("К концу секции " + currentSection + " остались неопределёнными метки, на которые есть ссылки: " + undefined, text);
    }

    private boolean isInsideProgram(int address) {
        return address >= startAddress && address < locationCounter;
    }

    private void processWord(CodeLine line, String text) {
        requireOneOperand(line, text);
        int value = parseDecimal(line.firstOperand(), 0, MAX_WORD, text);

        emit(WORD_SIZE, hex(value, 6), text);
    }

    private void processByte(CodeLine line, String text) {
        requireOneOperand(line, text);
        String operand = line.firstOperand();

        if (Operands.isCharacterString(operand)) {
            String symbols = Operands.content(operand);
            emitString(symbols.length(), Operands.toAsciiHex(symbols), text);
            return;
        }

        if (Operands.isHexString(operand)) {
            String digits = Operands.content(operand).toUpperCase();
            emitString(digits.length() / 2, digits, text);
            return;
        }

        if (Numbers.parseDecimal(operand) == null)
            throw error("Невозможно преобразовать первый операнд в число, символьную или шестнадцатеричную строку", text);

        int value = parseDecimal(operand, 0, MAX_BYTE, text);
        emit(1, hex(value, 2), text);
    }

    private void processReserve(CodeLine line, String text) {
        requireOneOperand(line, text);
        boolean words = line.command().equals("RESW");
        int count = parseDecimal(line.firstOperand(), 1, words ? MAX_RECORD_LENGTH / WORD_SIZE : MAX_RECORD_LENGTH, text);

        emit(words ? count * WORD_SIZE : count, null, text);
    }

    private void emitString(int size, String code, String text) {
        if (size > MAX_RECORD_LENGTH)
            throw error("Длина строки не должна превышать " + MAX_RECORD_LENGTH + " байт (сейчас " + size + ")", text);
        emit(size, code, text);
    }

    private void processCommand(CodeLine line, String text) {
        Command command = findCommand(line.command());
        if (command == null)
            throw error("Неизвестная команда", text);

        switch (command.length()) {
            case 1 -> processCommandWithoutOperands(command, line, text);
            case 2 -> processShortCommand(command, line, text);
            default -> processLongCommand(command, line, text);
        }
    }

    private void processCommandWithoutOperands(Command command, CodeLine line, String text) {
        if (line.firstOperand() != null)
            throw error("Ожидается ноль операндов", text);

        emit(command.length(), opcode(command, ADDRESSING_VALUE), text);
    }

    private void processShortCommand(Command command, CodeLine line, String text) {
        String first = line.firstOperand();
        String second = line.secondOperand();

        if (first == null)
            throw error("Ожидается минимум один операнд, но было получено ноль", text);

        if (second != null) {
            if (!Operands.isRegister(first) || !Operands.isRegister(second))
                throw error("Неверный формат команды. Ожидалось два регистра", text);

            emit(command.length(), opcode(command, ADDRESSING_VALUE)
                    + hex(Operands.registerNumber(first.toUpperCase()), 1)
                    + hex(Operands.registerNumber(second.toUpperCase()), 1), text);
            return;
        }

        int value = parseDecimal(first, 0, MAX_BYTE, text);
        emit(command.length(), opcode(command, ADDRESSING_VALUE) + hex(value, 2), text);
    }

    private void processLongCommand(Command command, CodeLine line, String text) {
        requireOneOperand(line, text);
        String operand = line.firstOperand();

        if (Operands.isRelative(operand)) {
            String target = Operands.relativeTarget(operand);
            if (!looksLikeLabel(target))
                throw error("В квадратных скобках относительной адресации ожидается метка", text);
            processLabelOperand(command, target, ADDRESSING_RELATIVE, text);
            return;
        }

        if (looksLikeLabel(operand)) {
            processLabelOperand(command, operand, ADDRESSING_DIRECT, text);
            return;
        }

        Integer value = Numbers.parseDecimal(operand);
        if (value == null || value > MAX_WORD)
            throw error("Недопустимое значение операнда", text);

        emit(command.length(), opcode(command, ADDRESSING_VALUE) + hex(value, 6), text);
    }

    private void processLabelOperand(Command command, String operand, int addressing, String text) {
        if (declaredExternalReferences.contains(operand.toUpperCase())) {
            if (addressing == ADDRESSING_RELATIVE)
                throw error("Внешнюю ссылку " + operand.toUpperCase() + " нельзя адресовать относительно:"
                        + " её адрес станет известен только при загрузке", text);
            if (!addressingMode.isDirectAllowed())
                throw error("Внешняя ссылка " + operand.toUpperCase() + " адресуется только прямо,"
                        + " а прямая адресация запрещена в режиме «" + addressingMode + "»", text);
            int address = locationCounter;
            emit(command.length(), opcode(command, ADDRESSING_DIRECT) + "000000", text);
            relocationTable.add(new RelocationEntry(address, operand.toUpperCase(), currentSection));
            return;
        }
        requireAddressingAllowed(addressing, text);
        String problem = labelProblem(operand);
        if (problem != null)
            throw error("Операнд не может быть меткой. " + problem, text);

        Symbol symbol = findSymbol(operand);
        if (symbol != null && symbol.address != null) {
            int commandAddress = locationCounter;
            emit(command.length(), opcode(command, addressing)
                    + encodeTarget(symbol.address, locationCounter + command.length(), addressing, text), text);
            if (addressing == ADDRESSING_DIRECT)
                relocationTable.add(new RelocationEntry(commandAddress, null, currentSection));
            return;
        }

        if (symbol == null) {
            symbol = new Symbol(operand.toUpperCase(), currentSection);
            symbolTable.put(symbolKey(symbol.name), symbol);
        }

        int commandAddress = locationCounter;
        emit(command.length(), opcode(command, addressing) + UNRESOLVED_OPERAND, text);
        symbol.pendingReferences.add(new PendingReference(commandAddress, objectCode.size() - 1,
                locationCounter, addressing, text));
        if (addressing == ADDRESSING_DIRECT)
            relocationTable.add(new RelocationEntry(commandAddress, null, currentSection));
    }

    private void requireAddressingAllowed(int addressing, String text) {
        if (addressing == ADDRESSING_DIRECT && !addressingMode.isDirectAllowed())
            throw error("Прямая адресация запрещена в режиме «" + addressingMode + "»:"
                    + " операнд-метку нужно записать в квадратных скобках [метка]", text);
        if (addressing == ADDRESSING_RELATIVE && !addressingMode.isRelativeAllowed())
            throw error("Относительная адресация [метка] запрещена в режиме «" + addressingMode + "»:"
                    + " операнд-метку нужно записать без квадратных скобок", text);
    }

    private static String encodeTarget(int target, int nextAddress, int addressing, String text) {
        if (addressing == ADDRESSING_DIRECT)
            return hex(target, 6);
        int offset = target - nextAddress;
        if (offset < -0x800000 || offset > 0x7FFFFF)
            throw error("Смещение до метки не помещается в 3 байта", text);
        return hex(offset & MAX_WORD, 6);
    }

    private static String opcode(Command command, int addressing) {
        return hex(command.code() << 2 | addressing, 2);
    }

    private void emit(int size, String code, String text) {
        if ((long) locationCounter + size > MAX_ADDRESS)
            throw error("Длина секции не помещается в 3 байта (максимум FFFFFF)", text);

        objectCode.add("T " + hex(locationCounter, 6) + " " + hex(size, 2) + (code == null ? "" : " " + code));
        locationCounter += size;
    }

    private void validateSymbolDefinition(String label, String text) {
        if (declaredExternalReferences.contains(label.toUpperCase()))
            throw error("Метка " + label + " объявлена в EXTREF как внешняя ссылка и не может определяться в этой секции", text);
        Symbol symbol = findSymbol(label);
        if (symbol != null && symbol.address != null)
            throw error("Такая метка уже есть в ТСИ", text);
        if (symbol != null)
            for (PendingReference reference : symbol.pendingReferences)
                encodeTarget(locationCounter, reference.nextCommandAddress(), reference.addressing(), reference.sourceText());
    }

    // Commit a definition only after the labelled instruction/data has been validated and emitted.
    private void defineSymbol(String label, int address) {
        Symbol symbol = findSymbol(label);
        if (symbol == null) {
            symbol = new Symbol(label, currentSection);
            symbolTable.put(symbolKey(label), symbol);
        }
        symbol.address = address;
        resolvePendingReferences(symbol);
    }

    private void resolvePendingReferences(Symbol symbol) {
        for (PendingReference reference : symbol.pendingReferences) {
            String address = encodeTarget(symbol.address, reference.nextCommandAddress(),
                    reference.addressing(), reference.sourceText());
            String record = objectCode.get(reference.recordIndex());
            objectCode.set(reference.recordIndex(), record.substring(0, record.length() - address.length()) + address);
        }
        symbol.pendingReferences.clear();
    }

    private CodeLine parseSourceLine(List<String> words) {
        String first = words.get(0);
        String second = words.size() > 1 ? words.get(1) : null;
        String third = words.size() > 2 ? words.get(2) : null;
        String fourth = words.size() > 3 ? words.get(3) : null;

        switch (words.size()) {
            case 1 -> {
                if (isCommand(first) || first.equalsIgnoreCase("END") || first.equalsIgnoreCase("CSECT"))
                    return new CodeLine(null, first.toUpperCase(), null, null);
            }
            case 2 -> {
                if (isLabel(first) && (isCommand(second) || second.equalsIgnoreCase("START")
                        || second.equalsIgnoreCase("END") || second.equalsIgnoreCase("CSECT")))
                    return new CodeLine(first.toUpperCase(), second.toUpperCase(), null, null);
                if (isCommand(first) || isDirective(first))
                    return new CodeLine(null, first.toUpperCase(), second, null);
            }
            case 3 -> {
                if (isLabel(first) && (isCommand(second) || isDirective(second)))
                    return new CodeLine(first.toUpperCase(), second.toUpperCase(), third, null);
                if (isCommand(first) || isExternalDirective(first))
                    return new CodeLine(null, first.toUpperCase(), second, third);
            }
            case 4 -> {
                if (isLabel(first) && (isCommand(second) || isExternalDirective(second)))
                    return new CodeLine(first.toUpperCase(), second.toUpperCase(), third, fourth);
            }
            default -> {
            }
        }

        throw malformedLineError(words);
    }

    private AssemblerException malformedLineError(List<String> words) {
        String text = String.join(" ", words);
        String first = words.get(0);
        String second = words.size() > 1 ? words.get(1) : null;

        if (words.size() > 4)
            return error("Слишком много слов в строке: ожидается [метка] команда [операнд1] [операнд2]", text);

        if (second != null && (!isCommandOrDirective(first) || isCommandOrDirective(second))) {
            String reason = labelProblem(first);
            if (reason != null)
                return error(reason, text);
        }

        if (isCommandOrDirective(first))
            return error("Неверное количество операндов для " + first.toUpperCase(), text);

        if (second != null && isCommandOrDirective(second)) {
            if (isLabel(first))
                return error("Неверное количество операндов для " + second.toUpperCase(), text);
            return error("Некорректная метка \"" + first + "\": метка должна начинаться с латинской буквы"
                    + " и состоять из латинских букв, цифр и _", text);
        }

        if (second != null && isLabel(first)) {
            String command = words.subList(2, words.size()).stream()
                    .filter(this::isCommandOrDirective).findFirst().orElse(null);
            if (command != null)
                return error("Лишнее слово \"" + second + "\" между меткой и командой " + command.toUpperCase(), text);
            if (!looksLikeOperand(second))
                return unknownCommandError(second, text);
        }

        return unknownCommandError(first, text);
    }

    private AssemblerException unknownCommandError(String word, String text) {
        if (LABEL.matcher(word).matches())
            return error("Неизвестная команда: \"" + word + "\"", text);

        String message = "Неизвестная команда: \"" + word + "\" — имя команды должно начинаться с латинской буквы"
                + " и состоять из латинских букв и цифр";

        String upper = word.toUpperCase();
        String known = Stream.concat(commands.stream().map(Command::name), DIRECTIVES.stream())
                .map(String::toUpperCase)
                .filter(name -> upper.length() > name.length() && upper.endsWith(name))
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
        if (known != null)
            message += ". Лишние символы \"" + word.substring(0, word.length() - known.length()) + "\" перед " + known;

        return error(message, text);
    }

    private boolean isCommandOrDirective(String word) {
        return isCommand(word) || isDirective(word);
    }

    private static boolean looksLikeOperand(String word) {
        return LABEL.matcher(word).matches()
                || Numbers.parseDecimal(word) != null
                || Operands.isCharacterString(word)
                || Operands.isHexString(word)
                || Operands.isRelative(word);
    }

    private String labelProblem(String word) {
        if (!LABEL.matcher(word).matches())
            return null;
        if (word.length() > MAX_LABEL_LENGTH)
            return "Длина метки не должна превышать " + MAX_LABEL_LENGTH + " символов: \"" + word + "\"";
        if (Operands.isRegister(word))
            return "Имя метки не должно совпадать с именем регистра: \"" + word + "\"";
        if (isCommand(word))
            return "Имя метки не должно совпадать с именем команды: \"" + word + "\"";
        if (isDirective(word))
            return "Имя метки не должно совпадать с именем директивы: \"" + word + "\"";
        return null;
    }

    private String symbolKey(String name) {
        return currentSection + ":" + name.toUpperCase();
    }

    private Symbol findSymbol(String name) {
        return symbolTable.get(symbolKey(name));
    }

    private Command findCommand(String name) {
        return commands.stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private boolean isCommand(String word) {
        return findCommand(word) != null;
    }

    private static boolean isDirective(String word) {
        return DIRECTIVES.contains(word.toUpperCase());
    }

    private boolean isLabel(String word) {
        return word.length() <= MAX_LABEL_LENGTH
                && LABEL.matcher(word).matches()
                && !Operands.isRegister(word)
                && !isCommand(word)
                && !isDirective(word);
    }

    private boolean looksLikeLabel(String word) {
        return LABEL.matcher(word).matches();
    }

    private static void requireOneOperand(CodeLine line, String text) {
        if (line.firstOperand() == null)
            throw error("Ожидается один операнд, но было получено ноль", text);
        if (line.secondOperand() != null)
            throw error("Ожидается один операнд, но найдено два", text);
    }

    private static int parseDecimal(String operand, int min, int max, String text) {
        Integer value = Numbers.parseDecimal(operand);
        if (value == null)
            throw error("Невозможно преобразовать первый операнд в число", text);
        if (value < min || value > max)
            throw error("Значение первого операнда выходит за границы допустимого диапазона (" + min + "-" + max + ")", text);
        return value;
    }

    private static <T> void requireUnique(List<Command> commands, Function<Command, T> key, String message) {
        Set<T> seen = new HashSet<>();
        for (Command command : commands)
            if (!seen.add(key.apply(command)))
                throw new AssemblerException(message);
    }

    private static AssemblerException error(String message, String text) {
        return new AssemblerException(message + ": " + text);
    }
}
