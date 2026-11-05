package com.botmaker.core.project;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassKindTest {

    @Test
    void eachKindIsADeclarationOfItsName() {
        assertEquals("package com.mybot.util;\n\npublic record Point() {\n}\n",
                ClassKind.RECORD.source("com.mybot.util", "Point"));
        assertEquals("package com.mybot;\n\npublic class Gold {\n}\n", ClassKind.CLASS.source("com.mybot", "Gold"));
        assertEquals("package com.mybot;\n\npublic interface Step {\n}\n",
                ClassKind.INTERFACE.source("com.mybot", "Step"));
        assertEquals("package com.mybot;\n\npublic enum Town {\n}\n", ClassKind.ENUM.source("com.mybot", "Town"));
        assertEquals(ClassKind.ENUM, ClassKind.fromId(" Enum ").orElseThrow());
        assertTrue(ClassKind.fromId("annotation").isEmpty());
    }
}
