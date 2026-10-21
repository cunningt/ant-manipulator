package org.jboss.pnc.antmanipulator.report;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.pnc.antmanipulator.gav.CorrelatedGav;
import org.jboss.pnc.antmanipulator.gav.GavCorrelator;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav;
import org.jboss.pnc.antmanipulator.gav.VersionReconciler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Builds our own richer, pre-alignment discovery report — written to {@code discoveryReportJSONOutputFile}.
 * No PME/GME equivalent (see {@link PmeAlignmentReport} for the PME-shaped {@code alignmentReport.json}
 * PNC/Repour consume).
 *
 * <p>
 * This is a <b>discovery/analysis</b> report, produced before any changes are applied: every coordinate we
 * found, with provenance, conflict flags, and — crucially for Ant — the {@link VersionReconciler
 * reconciliation} of each tokenised version to its concrete value(s). This is exactly the input a later
 * alignment step would annotate with before→after edits.
 */
public final class AlignmentReport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Matches the report's historical hand-rolled format: 2-space indent, {@code "key": value} (no space before the
     * colon).
     */
    private static final DefaultPrettyPrinter PRETTY_PRINTER;

    static {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        Separators separators = Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                .withObjectEmptySeparator("")
                .withArrayEmptySeparator("");
        PRETTY_PRINTER = new DefaultPrettyPrinter()
                .withObjectIndenter(indenter)
                .withArrayIndenter(indenter)
                .withSeparators(separators);
    }

    private final Map<String, Object> root;

    private AlignmentReport(Map<String, Object> root) {
        this.root = root;
    }

    public static AlignmentReport build(
            File rootBuildFile,
            List<ResolvedGav> gavs,
            GavCorrelator.Result correlated,
            VersionReconciler reconciler) {

        final Map<String, Object> report = new LinkedHashMap<>();
        report.put("rootBuildFile", rootBuildFile.getAbsolutePath());
        report.put("generatedAt", Instant.now().toString());

        // Reconcile every distinct tokenised version once, and tally outcomes for the summary.
        final Set<String> distinctTokens = new LinkedHashSet<>();
        for (ResolvedGav g : gavs) {
            if (g.hasUnresolvedVersion()) {
                distinctTokens.add(g.getVersion());
            }
        }
        int resolvedCount = 0;
        int ambiguousCount = 0;
        int unresolvedCount = 0;
        for (String token : distinctTokens) {
            VersionReconciler.Resolution r = reconciler.reconcile(token);
            if (r.isUnresolved()) {
                unresolvedCount++;
            } else if (r.isAmbiguous()) {
                ambiguousCount++;
            } else {
                resolvedCount++;
            }
        }

        final Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("coordinateCandidates", gavs.size());
        summary.put("artifacts", correlated.getArtifacts().size());
        summary.put("conflicts", correlated.getConflicts().size());
        summary.put("projectVersionCandidates", correlated.getProjectVersionCandidates().size());
        summary.put("tokenisedVersions", distinctTokens.size());
        summary.put("tokensResolved", resolvedCount);
        summary.put("tokensAmbiguous", ambiguousCount);
        summary.put("tokensUnresolved", unresolvedCount);
        report.put("summary", summary);

        final List<Object> artifacts = new ArrayList<>();
        for (CorrelatedGav artifact : correlated.getArtifacts()) {
            artifacts.add(artifactToMap(artifact, reconciler));
        }
        report.put("artifacts", artifacts);

        final List<Object> projectVersions = new ArrayList<>();
        for (ResolvedGav pv : correlated.getProjectVersionCandidates()) {
            projectVersions.add(findingToMap(pv, reconciler));
        }
        report.put("projectVersionCandidates", projectVersions);

        return new AlignmentReport(report);
    }

    private static Map<String, Object> artifactToMap(CorrelatedGav artifact, VersionReconciler reconciler) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("groupId", artifact.getGroupId());
        m.put("artifactId", artifact.getArtifactId());
        m.put("primaryVersion", artifact.primaryVersion());
        m.put("conflict", artifact.isConflicting());
        m.put("confidence", artifact.bestConfidence().name());
        m.put("resolvedVersions", new ArrayList<Object>(artifact.resolvedVersions()));
        m.put("tokenisedVersions", new ArrayList<Object>(artifact.tokenizedVersions()));

        final List<Object> reconciled = new ArrayList<>();
        for (String token : artifact.tokenizedVersions()) {
            reconciled.add(resolutionToMap(reconciler.reconcile(token)));
        }
        m.put("reconciled", reconciled);

        final List<Object> findings = new ArrayList<>();
        for (ResolvedGav f : artifact.getFindings()) {
            findings.add(findingToMap(f, reconciler));
        }
        m.put("findings", findings);
        return m;
    }

    private static Map<String, Object> findingToMap(ResolvedGav f, VersionReconciler reconciler) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("groupId", f.getGroupId());
        m.put("artifactId", f.getArtifactId());
        m.put("version", f.getVersion());
        m.put("sourceKind", f.getKind().name());
        m.put("confidence", f.getConfidence().name());
        m.put("sourceFile", f.getSourceFile() == null ? null : f.getSourceFile().getAbsolutePath());
        m.put("detail", f.getDetail());
        if (f.hasUnresolvedVersion()) {
            m.put("reconciled", resolutionToMap(reconciler.reconcile(f.getVersion())));
        }
        return m;
    }

    private static Map<String, Object> resolutionToMap(VersionReconciler.Resolution r) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", r.getOriginal());
        m.put("resolved", new ArrayList<Object>(r.getResolved()));
        m.put("unresolved", new ArrayList<Object>(r.getRemaining()));
        m.put("ambiguous", r.isAmbiguous());
        m.put("fullyResolved", r.isResolved());
        return m;
    }

    public String toJson() {
        try {
            return MAPPER.writer(PRETTY_PRINTER).writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize alignment report", e);
        }
    }

    /** Write the JSON report to {@code file}, returning the file for convenience. */
    public File writeTo(File file) throws IOException {
        Files.write(file.toPath(), toJson().getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
