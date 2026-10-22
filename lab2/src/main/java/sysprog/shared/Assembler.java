package sysprog.shared;

import static sysprog.shared.helpers.Numbers.hex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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

public class Assembler {
    private static final int START_ADDRESS = 0;
    private static final int MAX_ADDRESS = 0xFFFFFF;
    private static final int MAX_WORD = 0xFFFFFF;
    private static final int MAX_BYTE = 0xFF;
    private static final int MAX_RECORD_LENGTH = 0xFF;
    private static final int WORD_SIZE = 3;
    private static final int MIN_OFFSET = -0x800000;
    private static final int MAX_OFFSET = 0x7FFFFF;

    private static final int ADDRESSING_VALUE = 0b00;
    private static final int ADDRESSING_DIRECT = 0b01;
    private static final int ADDRESSING_RELATIVE = 0b10;

    private static final int MAX_LABEL_LENGTH = 10;
    private static final Pattern LABEL = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final List<String> DIRECTIVES = List.of("START", "END", "WORD", "BYTE", "RESB", "RESW");

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

    private final List<SymbolicName> symbolTable = new ArrayList<>();
    private final List<Integer> relocationTable = new ArrayList<>();
    private AddressingMode addressingMode = AddressingMode.MIXED;
    private int entryAddress;
    private int locationCounter;

    public List<Command> getCommands() {
        return commands;
    }

    public List<SymbolicName> getSymbolTable() {
        return Collections.unmodifiableList(symbolTable);
    }

    public List<Integer> getRelocationTable() {
        return Collections.unmodifiableList(relocationTable);
    }

    public int getEntryAddress() {
        return entryAddress;
    }

    public AddressingMode getAddressingMode() {
        return addressingMode;
    }

    public void setAddressingMode(AddressingMode addressingMode) {
        this.addressingMode = addressingMode;
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

    public List<String> firstPass(List<List<String>> source) {
        symbolTable.clear();
        relocationTable.clear();
        entryAddress = START_ADDRESS;
        locationCounter = START_ADDRESS;

        List<String> intermediate = new ArrayList<>();
        boolean started = false;
        boolean ended = false;

        for (List<String> words : source) {
            String text = String.join(" ", words);

            if (ended)
                throw error("Текст после директивы END", text);

            CodeLine line = parseSourceLine(words);

            if (!started && !line.command().equals("START"))
                throw error("Программа должна начинаться с директивы START", text);

            if (started && line.label() != null)
                defineSymbol(line.label(), text);

            switch (line.command()) {
                case "START" -> {
                    if (started)
                        throw error("START должен быть единственным, в начале исходного кода", text);
                    intermediate.add(processStart(line, text));
                    started = true;
                }
                case "END" -> {
                    processEnd(line, text);
                    intermediate.add("END " + hex(entryAddress, 6));
                    ended = true;
                }
                case "WORD" -> intermediate.add(processWord(line, text));
                case "BYTE" -> intermediate.add(processByte(line, text));
                case "RESB", "RESW" -> intermediate.add(processReserve(line, text));
                default -> intermediate.add(processCommand(line, text));
            }
        }

        if (!started)
            throw new AssemblerException("Не найдена директива START.");
        if (!ended)
            throw new AssemblerException("Не найдена директива END (точка входа в программу).");

        return intermediate;
    }

    public List<String> secondPass(List<List<String>> intermediate) {
        if (intermediate.isEmpty())
            throw new AssemblerException("Вспомогательная таблица пуста");

        relocationTable.clear();

        List<String> objectCode = new ArrayList<>();
        objectCode.add(headerRecord(intermediate.get(0)));

        for (List<String> words : intermediate.subList(1, intermediate.size())) {
            if (words.get(0).equalsIgnoreCase("END"))
                continue;
            objectCode.add(textRecord(words));
        }

        if (!isInsideProgram(entryAddress))
            throw new AssemblerException("Некорректный адрес входа в программу: " + hex(entryAddress, 6));

        for (int address : relocationTable)
            objectCode.add("M " + hex(address, 6));

        objectCode.add("E " + hex(entryAddress, 6));
        return objectCode;
    }

    private String processStart(CodeLine line, String text) {
        if (line.label() == null)
            throw error("Перед директивой START должна быть метка (имя программы)", text);

        String operand = line.firstOperand();
        if (operand != null && !Integer.valueOf(START_ADDRESS).equals(Numbers.parseHex(operand)))
            throw error("Программа в перемещаемом формате ассемблируется с адреса 0:"
                    + " операнд START должен отсутствовать или быть равен 0", text);

        return line.label() + " START " + hex(START_ADDRESS, 6);
    }

    private void processEnd(CodeLine line, String text) {
        if (line.label() != null)
            throw error("Перед директивой END не должно быть метки: она указывала бы за пределы программы", text);
        if (locationCounter == START_ADDRESS)
            throw error("Программа не содержит ни одной команды или данных", text);

        entryAddress = resolveEntryAddress(line.firstOperand(), text);

        if (!isInsideProgram(entryAddress))
            throw error("Адрес входа в программу должен лежать в её пределах ("
                    + hex(START_ADDRESS, 6) + "-" + hex(locationCounter - 1, 6) + ")", text);
    }

    private int resolveEntryAddress(String operand, String text) {
        if (operand == null)
            return START_ADDRESS;

        SymbolicName symbol = isLabel(operand) ? findSymbol(operand) : null;
        if (symbol != null)
            return symbol.address();

        Integer address = Numbers.parseHex(operand);
        if (address != null)
            return address;

        if (isLabel(operand))
            throw error("Метка точки входа не найдена в ТСИ", text);
        throw error("Невозможно преобразовать операнд END в метку или 16-ричный адрес входа в программу", text);
    }

    private boolean isInsideProgram(int address) {
        return address >= START_ADDRESS && address < locationCounter;
    }

    private String processWord(CodeLine line, String text) {
        requireOneOperand(line, text);
        int value = parseDecimal(line.firstOperand(), 0, MAX_WORD, text);

        return allocate(WORD_SIZE, "WORD " + hex(value, 6), text);
    }

    private String processByte(CodeLine line, String text) {
        requireOneOperand(line, text);
        String operand = line.firstOperand();

        if (Operands.isCharacterString(operand))
            return allocateString(Operands.content(operand).length(), "BYTE C" + operand.substring(1), text);

        if (Operands.isHexString(operand)) {
            String digits = Operands.content(operand).toUpperCase();
            return allocateString(digits.length() / 2, "BYTE X\"" + digits + "\"", text);
        }

        if (Numbers.parseDecimal(operand) == null)
            throw error("Невозможно преобразовать первый операнд в число, символьную или шестнадцатеричную строку", text);

        int value = parseDecimal(operand, 0, MAX_BYTE, text);
        return allocate(1, "BYTE " + hex(value, 2), text);
    }

    private String processReserve(CodeLine line, String text) {
        requireOneOperand(line, text);
        boolean words = line.command().equals("RESW");
        int count = parseDecimal(line.firstOperand(), 1, words ? MAX_RECORD_LENGTH / WORD_SIZE : MAX_RECORD_LENGTH, text);
        int size = words ? count * WORD_SIZE : count;

        return allocate(size, line.command() + " " + hex(count, 2), text);
    }

    private String allocateString(int size, String body, String text) {
        if (size > MAX_RECORD_LENGTH)
            throw error("Длина строки не должна превышать " + MAX_RECORD_LENGTH + " байт (сейчас " + size + ")", text);
        return allocate(size, body, text);
    }

    private String processCommand(CodeLine line, String text) {
        Command command = findCommand(line.command());
        if (command == null)
            throw error("Неизвестная команда", text);

        return switch (command.length()) {
            case 1 -> processCommandWithoutOperands(command, line, text);
            case 2 -> processShortCommand(command, line, text);
            default -> processLongCommand(command, line, text);
        };
    }

    private String processCommandWithoutOperands(Command command, CodeLine line, String text) {
        if (line.firstOperand() != null)
            throw error("Ожидается ноль операндов", text);

        return allocate(command.length(), opcode(command, ADDRESSING_VALUE), text);
    }

    private String processShortCommand(Command command, CodeLine line, String text) {
        String first = line.firstOperand();
        String second = line.secondOperand();

        if (first == null)
            throw error("Ожидается минимум один операнд, но было получено ноль", text);

        if (second != null) {
            if (!Operands.isRegister(first) || !Operands.isRegister(second))
                throw error("Неверный формат команды. Ожидалось два регистра", text);

            return allocate(command.length(),
                    opcode(command, ADDRESSING_VALUE) + " " + first.toUpperCase() + " " + second.toUpperCase(), text);
        }

        int value = parseDecimal(first, 0, MAX_BYTE, text);
        return allocate(command.length(), opcode(command, ADDRESSING_VALUE) + " " + hex(value, 2), text);
    }

    private String processLongCommand(Command command, CodeLine line, String text) {
        requireOneOperand(line, text);
        String operand = line.firstOperand();

        if (Operands.isRelative(operand)) {
            if (!looksLikeLabel(Operands.relativeTarget(operand)))
                throw error("В квадратных скобках относительной адресации ожидается метка", text);
            requireAddressingAllowed(ADDRESSING_RELATIVE, text);
            return allocate(command.length(), opcode(command, ADDRESSING_RELATIVE) + " " + operand, text);
        }

        if (looksLikeLabel(operand)) {
            requireAddressingAllowed(ADDRESSING_DIRECT, text);
            return allocate(command.length(), opcode(command, ADDRESSING_DIRECT) + " " + operand, text);
        }

        Integer value = Numbers.parseDecimal(operand);
        if (value == null || value > MAX_WORD)
            throw error("Недопустимое значение операнда", text);

        return allocate(command.length(), opcode(command, ADDRESSING_VALUE) + " " + hex(value, 6), text);
    }

    private void requireAddressingAllowed(int addressing, String text) {
        if (addressing == ADDRESSING_DIRECT && !addressingMode.isDirectAllowed())
            throw error("Прямая адресация запрещена в режиме «" + addressingMode + "»:"
                    + " операнд-метку нужно записать в квадратных скобках [метка]", text);
        if (addressing == ADDRESSING_RELATIVE && !addressingMode.isRelativeAllowed())
            throw error("Относительная адресация [метка] запрещена в режиме «" + addressingMode + "»:"
                    + " операнд-метку нужно записать без квадратных скобок", text);
    }

    private static String opcode(Command command, int addressing) {
        return hex(command.code() << 2 | addressing, 2);
    }

    private String allocate(int size, String body, String text) {
        if ((long) locationCounter + size - 1 > MAX_ADDRESS)
            throw error("Произошло переполнение выделенной памяти", text);

        String line = hex(locationCounter, 6) + " " + body;
        locationCounter += size;
        return line;
    }

    private String headerRecord(List<String> startLine) {
        return "H " + startLine.get(0) + " " + hex(START_ADDRESS, 6) + " " + hex(locationCounter - START_ADDRESS, 6);
    }

    private String textRecord(List<String> words) {
        String text = String.join(" ", words);
        if (words.size() < 2 || words.size() > 4)
            throw error("Неверный формат команды", text);

        Integer address = Numbers.parseHex(words.get(0));
        if (address == null)
            throw error("Некорректный адрес во вспомогательной таблице", text);

        String operation = words.get(1).toUpperCase();
        List<String> operands = words.subList(2, words.size());

        String body = switch (operation) {
            case "WORD" -> hex(WORD_SIZE, 2) + " " + operands.get(0);
            case "BYTE" -> encodeByte(operands.get(0), text);
            case "RESB" -> hex(parseReserveCount(operands, text), 2);
            case "RESW" -> hex(parseReserveCount(operands, text) * WORD_SIZE, 2);
            default -> encodeCommand(address, operation, operands, text);
        };

        return "T " + hex(address, 6) + " " + body;
    }

    private static String encodeByte(String operand, String text) {
        if (Operands.isCharacterString(operand)) {
            String symbols = Operands.content(operand);
            return hex(symbols.length(), 2) + " " + Operands.toAsciiHex(symbols);
        }

        if (Operands.isHexString(operand)) {
            String digits = Operands.content(operand).toUpperCase();
            return hex(digits.length() / 2, 2) + " " + digits;
        }

        Integer value = Numbers.parseHex(operand);
        if (value == null)
            throw error("Невозможно преобразовать первый операнд в строку", text);
        return hex(1, 2) + " " + hex(value, 2);
    }

    private static int parseReserveCount(List<String> operands, String text) {
        Integer count = operands.isEmpty() ? null : Numbers.parseHex(operands.get(0));
        if (count == null)
            throw error("Невозможно преобразовать операнд в числовое значение", text);
        return count;
    }

    private String encodeCommand(int address, String operation, List<String> operands, String text) {
        Integer opcode = Numbers.parseHex(operation);
        if (opcode == null || opcode > 0xFF)
            throw error("Неизвестная команда", text);

        Command command = findCommand(opcode >> 2);
        if (command == null)
            throw error("Команда с таким кодом не найдена в ТКО", text);

        requireAddressingAllowed(opcode & 0b11, text);
        String operandCode = switch (opcode & 0b11) {
            case ADDRESSING_VALUE -> encodeValueOperands(operands, text);
            case ADDRESSING_DIRECT -> encodeDirectOperand(address, operands, text);
            case ADDRESSING_RELATIVE -> encodeRelativeOperand(address + command.length(), operands, text);
            default -> throw error("Неизвестный тип адресации", text);
        };

        return hex(command.length(), 2) + " " + operation + operandCode;
    }

    private static String encodeValueOperands(List<String> operands, String text) {
        if (operands.isEmpty())
            return "";

        if (operands.size() == 1)
            return operands.get(0);

        String first = operands.get(0);
        String second = operands.get(1);
        if (!Operands.isRegister(first) || !Operands.isRegister(second))
            throw error("Неверный формат команды. Ожидалось два регистра", text);

        return hex(Operands.registerNumber(first), 1) + hex(Operands.registerNumber(second), 1);
    }

    private String encodeDirectOperand(int commandAddress, List<String> operands, String text) {
        int target = resolveLabelOperand(operands, text);
        relocationTable.add(commandAddress);
        return hex(target, 6);
    }

    private String encodeRelativeOperand(int nextCommandAddress, List<String> operands, String text) {
        if (operands.size() != 1 || !Operands.isRelative(operands.get(0)))
            throw error("Ожидается операнд относительной адресации [метка]", text);

        int target = resolveLabelOperand(List.of(Operands.relativeTarget(operands.get(0))), text);
        int offset = target - nextCommandAddress;
        if (offset < MIN_OFFSET || offset > MAX_OFFSET)
            throw error("Смещение до метки не помещается в 3 байта", text);

        return hex(offset & MAX_WORD, 6);
    }

    private int resolveLabelOperand(List<String> operands, String text) {
        if (operands.size() != 1)
            throw error("Ожидается один операнд-метка", text);

        SymbolicName symbol = findSymbol(operands.get(0));
        if (symbol == null)
            throw error("Метка не найдена в ТСИ", text);

        return symbol.address();
    }

    private CodeLine parseSourceLine(List<String> words) {
        String first = words.get(0);
        String second = words.size() > 1 ? words.get(1) : null;
        String third = words.size() > 2 ? words.get(2) : null;
        String fourth = words.size() > 3 ? words.get(3) : null;

        switch (words.size()) {
            case 1 -> {
                if (isCommand(first) || first.equalsIgnoreCase("END"))
                    return new CodeLine(null, first.toUpperCase(), null, null);
            }
            case 2 -> {
                if (isLabel(first) && (isCommand(second) || second.equalsIgnoreCase("START") || second.equalsIgnoreCase("END")))
                    return new CodeLine(first.toUpperCase(), second.toUpperCase(), null, null);
                if (isCommand(first) || isDirective(first))
                    return new CodeLine(null, first.toUpperCase(), second, null);
            }
            case 3 -> {
                if (isLabel(first) && (isCommand(second) || isDirective(second)))
                    return new CodeLine(first.toUpperCase(), second.toUpperCase(), third, null);
                if (isCommand(first))
                    return new CodeLine(null, first.toUpperCase(), second, third);
            }
            case 4 -> {
                if (isLabel(first) && isCommand(second))
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

        if (second != null) {
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

    private void defineSymbol(String label, String text) {
        if (findSymbol(label) != null)
            throw error("Такая метка уже есть в ТСИ", text);

        symbolTable.add(new SymbolicName(label, locationCounter));
    }

    private SymbolicName findSymbol(String name) {
        return symbolTable.stream().filter(s -> s.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private Command findCommand(String name) {
        return commands.stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private Command findCommand(int code) {
        return commands.stream().filter(c -> c.code() == code).findFirst().orElse(null);
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
        return word.length() <= MAX_LABEL_LENGTH && LABEL.matcher(word).matches();
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
