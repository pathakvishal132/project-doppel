package com.azentio.doppel.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.azentio.doppel.brand.Brand;
import com.azentio.doppel.brand.BrandService;
import com.azentio.doppel.matching.Decision;
import com.azentio.doppel.matching.MatchResult;
import com.azentio.doppel.matching.MatchingService;

/**
 * Runs every row of labelled_handles.csv through the real MatchingService, using the brands seeded by
 * Flyway, and reports a confusion matrix. Positive class = SUSPICIOUS; predicted positive = REVIEW or BLOCK.
 * Nothing is hard-coded: the metrics are computed from the actual decisions.
 */
@SpringBootTest
class LabelledHandlesEvaluationTest {

    private static final double MIN_PRECISION = 0.90;
    private static final double MIN_RECALL = 0.85;

    @Autowired
    private MatchingService matchingService;
    @Autowired
    private BrandService brandService;

    record Row(String vpa, String displayName, String category, boolean suspicious) {
    }

    @Test
    void precisionAndRecallMeetAcceptanceCriteria() throws Exception {
        List<Row> rows = loadRows();
        List<Brand> brands = brandService.findAll();
        assertThat(rows).hasSizeGreaterThanOrEqualTo(120);

        int tp = 0, fp = 0, tn = 0, fn = 0;
        Map<String, int[]> perCategory = new TreeMap<>(); // [correct, total]
        List<String> errors = new ArrayList<>();

        for (Row row : rows) {
            MatchResult result = matchingService.match(row.vpa(), row.displayName(), brands);
            boolean flagged = result.decision() != Decision.ALLOW;
            boolean correct = flagged == row.suspicious();

            if (flagged && row.suspicious()) tp++;
            else if (flagged) fp++;
            else if (row.suspicious()) fn++;
            else tn++;

            int[] c = perCategory.computeIfAbsent(row.category(), k -> new int[2]);
            c[1]++;
            if (correct) {
                c[0]++;
            } else {
                errors.add(String.format("  %-3s %-32s %-28s %-15s -> %s %d %s", flagged ? "FP" : "FN",
                        row.vpa(), row.displayName(), row.category(), result.decision(), result.score(),
                        result.reasons()));
            }
        }

        double precision = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
        double accuracy = (double) (tp + tn) / rows.size();
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);

        StringBuilder report = new StringBuilder();
        report.append("\n================ Project Doppel – labelled_handles.csv evaluation ================\n");
        report.append(String.format("Rows: %d  (positive = SUSPICIOUS, predicted positive = REVIEW/BLOCK)%n",
                rows.size()));
        report.append("\nConfusion matrix:\n");
        report.append("                      predicted SUSPICIOUS   predicted BENIGN\n");
        report.append(String.format("  actual SUSPICIOUS   TP = %-17d FN = %d%n", tp, fn));
        report.append(String.format("  actual BENIGN       FP = %-17d TN = %d%n", fp, tn));
        report.append(String.format("%nPrecision: %.4f   Recall: %.4f   F1: %.4f   Accuracy: %.4f%n",
                precision, recall, f1, accuracy));
        report.append("\nPer-category accuracy:\n");
        perCategory.forEach((cat, c) -> report.append(
                String.format("  %-16s %3d/%-3d  %.4f%n", cat, c[0], c[1], (double) c[0] / c[1])));
        report.append("\nMisclassified rows (").append(errors.size()).append("):\n");
        errors.forEach(e -> report.append(e).append('\n'));
        report.append("==================================================================================\n");
        System.out.println(report);

        assertThat(precision).as("precision").isGreaterThanOrEqualTo(MIN_PRECISION);
        assertThat(recall).as("recall").isGreaterThanOrEqualTo(MIN_RECALL);
    }

    private List<Row> loadRows() throws Exception {
        List<Row> rows = new ArrayList<>();
        try (var in = getClass().getResourceAsStream("/labelled_handles.csv");
                var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            assertThat(header).isEqualTo("vpa,display_name,category,label");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(",", -1);
                assertThat(f).as("columns in: " + line).hasSize(4);
                assertThat(f[3]).isIn("SUSPICIOUS", "BENIGN");
                rows.add(new Row(f[0].trim(), f[1].trim(), f[2].trim(), f[3].trim().equals("SUSPICIOUS")));
            }
        }
        return rows;
    }
}
