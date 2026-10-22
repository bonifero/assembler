package sysprog.app;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Samples {
    public static final String DEFAULT_SAMPLE = """
            PROG  START   100
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

    public static final String FORWARD_REFERENCES_SAMPLE = """
            PROG  START   1000
                LOADR1  X
                LOADR2  Y
                CALL    SUBR
                SAVER1  X
                JMP     FIN
            SUBR  ADD     R1  R2
                SAVER1  Y
                RET
            FIN   INT     33
            X   WORD    5
            Y   WORD    7
                END
            """;

    public static final String ALL_DIRECTIVES_SAMPLE = """
            PROG  START   1000
            A1  RESB    10
            A2  RESW    20
            B1  WORD    4096
            B2  BYTE    X"2F4C008A"
            B3  BYTE    C"Hello world"
            B4  BYTE    128
            L1  LOADR1  B1
                LOADR2  B4
                ADD     R1  R2
                SAVER1  A2
                INT     200
                END     L1
            """;

    public static final String ALL_COMMANDS_SAMPLE = """
            MAIN  START   2000
            BEGIN CALL    SUBR
                INT     33
            SUBR  LOADR1  X1
                LOADR2  X2
                MUL     R1  R2
                SUB     R1  R2
                SAVER1  RES
                RET
            X1  WORD    7
            X2  WORD    3
            RES RESW    1
                END     BEGIN
            """;

    public static final String DUPLICATE_LABEL_SAMPLE = """
            PROG  START   100
                JMP     L1
            B1  WORD    40
            B1  BYTE    12
            L1  LOADR1  B1
                END
            """;

    public static final String UNKNOWN_COMMAND_SAMPLE = """
            PROG  START   100
            L1  LOADR1  B1
                MOV     R1  R2
            B1  WORD    40
                END
            """;

    public static final String NO_END_SAMPLE = """
            PROG  START   100
            L1  LOADR1  B1
            B1  WORD    40
            """;

    public static final String TEXT_AFTER_END_SAMPLE = """
            PROG  START   100
            L1  LOADR1  B1
            B1  WORD    40
                END
                JMP     L1
            """;

    public static final String UNDEFINED_LABEL_SAMPLE = """
            PROG  START   100
            L1  LOADR1  B1
                JMP     L9
                CALL    L9
            B1  WORD    40
                END
            """;

    public static final String RELATIVE_ADDRESSING_SAMPLE = """
            PROG  START   100
            L1  LOADR1  [B1]
            B1  WORD    40
                END
            """;

    public static final Map<String, String> ALL = new LinkedHashMap<>();

    static {
        ALL.put("По умолчанию (без ошибок)", DEFAULT_SAMPLE);
        ALL.put("Много ссылок вперёд (без ошибок)", FORWARD_REFERENCES_SAMPLE);
        ALL.put("Все директивы (без ошибок)", ALL_DIRECTIVES_SAMPLE);
        ALL.put("Все команды ТКО (без ошибок)", ALL_COMMANDS_SAMPLE);
        ALL.put("Ошибка: повторная метка", DUPLICATE_LABEL_SAMPLE);
        ALL.put("Ошибка: неизвестная команда", UNKNOWN_COMMAND_SAMPLE);
        ALL.put("Ошибка: нет END", NO_END_SAMPLE);
        ALL.put("Ошибка: текст после END", TEXT_AFTER_END_SAMPLE);
        ALL.put("Ошибка: метка не определена", UNDEFINED_LABEL_SAMPLE);
        ALL.put("Ошибка: [метка] в абсолютном формате", RELATIVE_ADDRESSING_SAMPLE);
    }

    private Samples() {
    }
}
