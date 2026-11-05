package com.botmaker.core.edit;

import com.botmaker.core.project.params.BotRecords;

import java.lang.reflect.Type;
import java.util.List;

/**
 * The types a variable may be declared as, as the client's Edit Variable dialog offers them (2026-11-05, plan phase
 * 9a): an assistant's retype chooses from the same list a user's does. Studio fills it from its type chooser.
 */
@FunctionalInterface
public interface DeclarableTypes {

    /** Offers nothing: every retype is refused. */
    DeclarableTypes NONE = records -> List.of();

    /** One type on offer: the label the chooser shows, and the type. */
    record Offered(String label, Type type) {
    }

    /** The types on offer for a bot whose own records are {@code records}. */
    List<Offered> offered(BotRecords records);
}
