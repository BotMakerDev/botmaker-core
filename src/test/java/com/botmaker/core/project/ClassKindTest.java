package com.botmaker.core.project;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassKindTest {

    @Test
    void eachKindIsADeclarationOfItsName() {
        assertEquals("package com.mybot.util;\n\npublic record Point() {\n}\n",
                ClassKind.RECORD.source("com.mybot.util", "Point"));
        assertEquals(ClassKind.ENUM, ClassKind.fromId(" Enum ").orElseThrow());
        assertTrue(ClassKind.fromId("annotation").isEmpty());
    }
}
