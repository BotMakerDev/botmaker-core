package com.botmaker.core.parser;

import com.botmaker.core.project.ProjectState;
import com.botmaker.java.grammar.SourceParser;
import com.botmaker.java.parse.JavaLevel;
import com.botmaker.java.types.ProjectAnalyzer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a block offers a number literal as: a hex integer's letters are digits, not suffixes. */
class NumberLiteralValueTypeTest {

    @ParameterizedTest
    @CsvSource({"0xE,int", "0xFF,int", "0xD,int", "0xFFL,long", "1f,float", "1d,double", "1e3,double", "7,int"})
    void aLiteralHasTheTypeOfItsForm(String literal, String type) {
        ProjectAnalyzer analyzer = new ProjectAnalyzer(null, new ProjectState());

        assertEquals(type, analyzer.valueTypeOf(SourceParser.expression(JavaLevel.LATEST, literal)).qualifiedName());
    }
}
