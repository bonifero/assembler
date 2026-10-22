package sysprog.shared.dto;

import sysprog.shared.helpers.Numbers;
import sysprog.shared.structure.Command;

public record CommandDto(String name, String code, String length) {

    public static CommandDto from(Command command) {
        return new CommandDto(command.name(), Numbers.hex(command.code(), 2), Numbers.hex(command.length(), 1));
    }
}
