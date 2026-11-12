package com.foodrescue.storage;

import com.foodrescue.exception.DataFileException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvFormatTest {

    @Test
    void valuesWithCommasQuotesAndLineBreaksSurviveARoundTrip() {
        List<String> values = List.of("plain", "a, b", "He said \"hi\"", "two\nlines", " padded ");
        String text = CsvFormat.line(List.of("c1", "c2", "c3", "c4", "c5")) + CsvFormat.line(values);

        List<CsvFormat.Row> rows = CsvFormat.parse(text, "test.csv");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).values()).isEqualTo(values);
        assertThat(rows.get(1).line()).isEqualTo(2);
    }

    @Test
    void nullIsWrittenAsAnEmptyField() {
        assertThat(CsvFormat.line(Arrays.asList("x", null, 3))).isEqualTo("x,,3\n");
        assertThat(CsvFormat.parse("x,,3\n", "t.csv").get(0).values()).containsExactly("x", "", "3");
    }

    @Test
    void blankLinesAndWindowsLineEndingsAreHandled() {
        List<CsvFormat.Row> rows = CsvFormat.parse("a,b\r\n\r\n1,2\r\n", "t.csv");

        assertThat(rows).extracting(CsvFormat.Row::values).containsExactly(List.of("a", "b"), List.of("1", "2"));
        assertThat(rows.get(1).line()).isEqualTo(3);
    }

    @Test
    void unclosedQuoteIsReportedWithFileAndLine() {
        assertThatThrownBy(() -> CsvFormat.parse("a,b\n1,\"oops\n", "users.csv"))
                .isInstanceOf(DataFileException.class)
                .hasMessageContaining("users.csv, line 2");
    }
}
