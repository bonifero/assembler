package sysprog.app;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Samples {
    public static final String DEFAULT_SAMPLE = """
            FIRST START
                EXTDEF  BUF   STR1
                EXTREF  STR2
            PROC  LOADR1  STR3
                LOADR2  STR2
                ADD     R1  R2
                SAVER1  REZ
                JMP     PROC
            STR1  WORD    3
            STR3  WORD    1
            REZ   RESW    1
            BUF   BYTE    C"Hello!"
                END
            """;

    public static final String EXTERNAL_RELATIVE_SAMPLE = """
            FIRST START
                EXTDEF  BUF   STR1
            PROC  LOADR1  [STR3]
                LOADR2  [STR1]
                ADD     R1  R2
                SAVER1  [REZ]
                JMP     [PROC]
            STR1  WORD    3
            STR3  WORD    1
            REZ   RESW    1
            BUF   BYTE    C"Hello!"
                END
            """;

    public static final String EXTERNAL_MIXED_SAMPLE = """
            FIRST START
                EXTDEF  BUF   STR1
                EXTREF  STR2  SUBR
            PROC  LOADR1  [STR3]
                LOADR2  STR2
                ADD     R1  R2
                SAVER1  REZ
                CALL    SUBR
                JMP     [PROC]
            STR1  WORD    3
            STR3  WORD    1
            REZ   RESW    1
            BUF   BYTE    C"Hello!"
                END
            """;

    public static final String DIRECT_SAMPLE = """
            PROG  START
                JMP     L1
            B1  WORD    40
            B3  BYTE    C"Hello!"
            B4  BYTE    12
            L1  LOADR1  B1
                LOADR2  B4
                ADD     R1  R2
                SAVER1  B1
                INT     200
                END
            """;

    public static final String RELATIVE_SAMPLE = """
            PROG  START
                JMP     [L1]
            B1  WORD    40
            B3  BYTE    C"Hello!"
            B4  BYTE    12
            L1  LOADR1  [B1]
                LOADR2  [B4]
                ADD     R1  R2
                SAVER1  [B1]
                INT     200
                END
            """;

    public static final String MIXED_SAMPLE = """
            PROG  START
                JMP     [L1]
            B1  WORD    40
            B3  BYTE    C"Hello!"
            B4  BYTE    12
            L1  LOADR1  B1
                LOADR2  [B4]
                ADD     R1  R2
                SAVER1  B1
                INT     200
                JMP     [L1]
                END
            """;

    public static final String ALL_DIRECTIVES_SAMPLE = """
            PROG  START   0
            A1  RESB    10
            A2  RESW    20
            B1  WORD    4096
            B2  BYTE    X"2F4C008A"
            B3  BYTE    C"Hello world"
            B4  BYTE    128
            L1  LOADR1  B1
                LOADR2  [B4]
                ADD     R1  R2
                SAVER1  A2
                INT     200
                END     L1
            """;

    public static final String ALL_COMMANDS_SAMPLE = """
            MAIN  START
            BEGIN CALL    [SUBR]
                INT     33
            SUBR  LOADR1  X1
                LOADR2  X2
                MUL     R1  R2
                SUB     R1  R2
                SAVER1  [RES]
                RET
            X1  WORD    7
            X2  WORD    3
            RES RESW    1
                END     BEGIN
            """;

    public static final String NONZERO_START_SAMPLE = """
            PROG  START   100
            L1  LOADR1  B1
            B1  WORD    40
                END
            """;

    public static final String DUPLICATE_LABEL_SAMPLE = """
            PROG  START
                JMP     L1
            B1  WORD    40
            B1  BYTE    12
            L1  LOADR1  B1
                END
            """;

    public static final String UNKNOWN_COMMAND_SAMPLE = """
            PROG  START
            L1  LOADR1  B1
                MOV     R1  R2
            B1  WORD    40
                END
            """;

    public static final String NO_END_SAMPLE = """
            PROG  START
            L1  LOADR1  B1
            B1  WORD    40
            """;

    public static final String TEXT_AFTER_END_SAMPLE = """
            PROG  START
            L1  LOADR1  B1
            B1  WORD    40
                END
                JMP     L1
            """;

    public static final String UNDEFINED_LABEL_SAMPLE = """
            PROG  START
            L1  LOADR1  B1
                JMP     L9
            B1  WORD    40
                END
            """;

    public static final String UNDEFINED_RELATIVE_LABEL_SAMPLE = """
            PROG  START
            L1  LOADR1  [B1]
                JMP     [L9]
            B1  WORD    40
                END
            """;

    public static final String EXTERNAL_AFTER_COMMAND_SAMPLE = """
            FIRST START
                EXTDEF  BUF
            PROC  LOADR1  BUF
                EXTREF  STR2
            BUF   WORD    3
                END
            """;

    public static final String UNDEFINED_EXTERNAL_NAME_SAMPLE = """
            FIRST START
                EXTDEF  BUF   STR9
            PROC  LOADR1  BUF
            BUF   WORD    3
                END
            """;

    public static final String RELATIVE_EXTERNAL_REFERENCE_SAMPLE = """
            FIRST START
                EXTREF  STR2
            PROC  LOADR1  [STR2]
                END
            """;

    public static final String EXTERNAL_REFERENCE_LABEL_SAMPLE = """
            FIRST START
                EXTREF  STR2
            PROC  LOADR1  STR2
            STR2  WORD    3
                END
            """;

    public static final Map<String, String> ALL = new LinkedHashMap<>();

    static {
        ALL.put("По умолчанию: внешние имена и ссылки, прямая адресация (без ошибок)", DEFAULT_SAMPLE);
        ALL.put("Внешние имена, только относительная адресация (без ошибок)", EXTERNAL_RELATIVE_SAMPLE);
        ALL.put("Внешние имена и ссылки, смешанная адресация (без ошибок)", EXTERNAL_MIXED_SAMPLE);
        ALL.put("Только прямая адресация, без внешних имён (без ошибок)", DIRECT_SAMPLE);
        ALL.put("Только относительная адресация, без внешних имён (без ошибок)", RELATIVE_SAMPLE);
        ALL.put("Смешанная адресация, без внешних имён (без ошибок)", MIXED_SAMPLE);
        ALL.put("Все директивы (без ошибок)", ALL_DIRECTIVES_SAMPLE);
        ALL.put("Все команды ТКО (без ошибок)", ALL_COMMANDS_SAMPLE);
        ALL.put("Ошибка: START не с адреса 0", NONZERO_START_SAMPLE);
        ALL.put("Ошибка: повторная метка", DUPLICATE_LABEL_SAMPLE);
        ALL.put("Ошибка: неизвестная команда", UNKNOWN_COMMAND_SAMPLE);
        ALL.put("Ошибка: нет END", NO_END_SAMPLE);
        ALL.put("Ошибка: текст после END", TEXT_AFTER_END_SAMPLE);
        ALL.put("Ошибка: EXTREF после команды", EXTERNAL_AFTER_COMMAND_SAMPLE);
        ALL.put("Ошибка: внешнее имя из EXTDEF не определено", UNDEFINED_EXTERNAL_NAME_SAMPLE);
        ALL.put("Ошибка: внешняя ссылка в [ ]", RELATIVE_EXTERNAL_REFERENCE_SAMPLE);
        ALL.put("Ошибка: метка совпадает с внешней ссылкой", EXTERNAL_REFERENCE_LABEL_SAMPLE);
        ALL.put("Ошибка 2-го прохода: метка не определена", UNDEFINED_LABEL_SAMPLE);
        ALL.put("Ошибка 2-го прохода: метка в [ ] не определена", UNDEFINED_RELATIVE_LABEL_SAMPLE);
    }

    private Samples() {
    }
}
