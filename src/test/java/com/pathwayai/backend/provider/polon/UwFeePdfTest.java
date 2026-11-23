package com.pathwayai.backend.provider.polon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The recorded official PDF (read 2026-11-23, Last-Modified 2026-10-05). Synthetic data is not used: this is the real layout. */
class UwFeePdfTest {

    static byte[] pdf() throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/polon/uw/tuition_fees_2026-2027_05.10.2026.pdf"));
    }

    static Map<String, Integer> wanted() {
        var m = new LinkedHashMap<String, Integer>();
        m.put("MACHINE LEARNING", 2);
        m.put("DATA SCIENCE AND BUSINESS ANALYTICS", 2);
        m.put("FINANCE, INTERNATIONAL INVESTMENT AND ACCOUNTING", 1);
        m.put("NO SUCH PROGRAMME", 2);
        return m;
    }

    @Test
    void machineLearningHasAnAttributableNonEuAmountInTheMasterColumn() throws Exception {
        var row = UwFeePdf.read(pdf(), wanted()).stream().filter(r -> r.name().equals("MACHINE LEARNING")).findFirst().orElseThrow();
        System.out.println("ROW " + row);
        assertThat(row.page()).isEqualTo(2);
        assertThat(row.text()).contains("free of charge").contains("3,300 EUR");
        assertThat(row.fee()).isNotNull();
        assertThat(row.fee().amount()).isEqualTo("3300");
        assertThat(row.fee().currency()).isEqualTo("EUR");
        assertThat(row.fee().academicYear()).isEqualTo("2026/2027");
        assertThat(row.fee().cycle()).isEqualTo(2);
    }

    @Test
    void rowsWithoutAPrintedCategoryAreNotStated() throws Exception {
        var rows = UwFeePdf.read(pdf(), wanted());
        for (String n : new String[] {"DATA SCIENCE AND BUSINESS ANALYTICS", "FINANCE, INTERNATIONAL INVESTMENT AND ACCOUNTING"}) {
            var row = rows.stream().filter(r -> r.name().equals(n)).findFirst().orElseThrow();
            System.out.println("ROW " + row);
            assertThat(row.fee()).as(n).isNull();
            assertThat(row.notStated()).contains("category is not stated");
        }
    }

    @Test
    void anAbsentProgrammeIsReportedNotInvented() throws Exception {
        var row = UwFeePdf.read(pdf(), wanted()).stream().filter(r -> r.name().equals("NO SUCH PROGRAMME")).findFirst().orElseThrow();
        assertThat(row.fee()).isNull();
        assertThat(row.notStated()).contains("not found");
    }

    @Test
    void theWrongCycleIsNeverAttributed() throws Exception {
        // Machine Learning is a master-level row: asking for it as a bachelor row must not return its amount
        var row = UwFeePdf.read(pdf(), Map.of("MACHINE LEARNING", 1)).get(0);
        assertThat(row.fee()).isNull();
        assertThat(row.notStated()).contains("other cycle");
    }
}
