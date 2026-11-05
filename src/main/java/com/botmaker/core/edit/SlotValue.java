package com.botmaker.core.edit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What a slot's editor gives — {@code 12}, {@code Pictures.ORE}, {@code hello} — read by the core against the slot's
 * type. It is never Java the core splices: a value that type cannot read is refused with the reason. On the wire it
 * is the bare string.
 */
public record SlotValue(String text) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public SlotValue {
        if (text == null) throw new IllegalArgumentException("a slot value needs its text; empty is a value");
    }

    @JsonValue
    @Override
    public String text() {
        return text;
    }
}
