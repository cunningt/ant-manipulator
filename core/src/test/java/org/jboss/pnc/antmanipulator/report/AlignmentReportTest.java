package org.jboss.pnc.antmanipulator.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Collections;

import org.jboss.pnc.antmanipulator.gav.GavCorrelator;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav.Confidence;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav.SourceKind;
import org.jboss.pnc.antmanipulator.gav.VersionReconciler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link AlignmentReport#toJson()}: now serialised with Jackson rather than the old
 * hand-rolled {@code Json} class, so these pin the exact pretty-printed layout (2-space indent,
 * {@code "key": value}, trailing newline) and string escaping that downstream consumers rely on.
 */
class AlignmentReportTest {

    @TempDir
    File tempDir;

    @Test
    void producesTwoSpaceIndentWithColonSpaceAndTrailingNewline() {
        File buildFile = new File(tempDir, "build.xml");
        GavCorrelator.Result correlated = new GavCorrelator().correlate(Collections.emptyList());
        VersionReconciler reconciler = VersionReconciler.forTree(tempDir, buildFile);

        String json = AlignmentReport.build(buildFile, Collections.emptyList(), correlated, reconciler).toJson();

        assertThat(json).startsWith("{\n  \"rootBuildFile\": ");
        assertThat(json).doesNotContain(" : "); // no space before the colon, unlike Jackson's default printer
        assertThat(json).endsWith("}\n");
        assertThat(json).contains("\"artifacts\": [],\n").contains("\"projectVersionCandidates\": []\n");
    }

    @Test
    void escapesSpecialCharactersInFindingDetail() {
        File buildFile = new File(tempDir, "build.xml");
        ResolvedGav finding = new ResolvedGav(
                "org.acme",
                "widget",
                "1.0",
                buildFile,
                "line with \"quotes\", a\\backslash and a\ttab",
                SourceKind.POM_TEMPLATE,
                Confidence.HIGH);
        GavCorrelator.Result correlated = new GavCorrelator().correlate(Collections.singletonList(finding));
        VersionReconciler reconciler = VersionReconciler.forTree(tempDir, buildFile);

        String json = AlignmentReport.build(buildFile, Collections.singletonList(finding), correlated, reconciler)
                .toJson();

        assertThat(json).contains("\"detail\": \"line with \\\"quotes\\\", a\\\\backslash and a\\ttab\"");
    }
}
