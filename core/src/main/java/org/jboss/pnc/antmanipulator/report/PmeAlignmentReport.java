package org.jboss.pnc.antmanipulator.report;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.commonjava.atlas.maven.ident.ref.SimpleProjectVersionRef;
import org.jboss.pnc.antmanipulator.gav.CorrelatedGav;
import org.jboss.pnc.antmanipulator.gav.GavCorrelator;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav;
import org.jboss.pnc.antmanipulator.gav.VersionReconciler;
import org.jboss.pnc.mavenmanipulator.common.json.ModulesItem;
import org.jboss.pnc.mavenmanipulator.common.json.PME;
import org.jboss.pnc.mavenmanipulator.common.util.JSONUtils;

/**
 * Builds {@code alignmentReport.json} in PME's own JSON shape — the {@code org.jboss.pnc.mavenmanipulator.common.json}
 * POJOs, reused verbatim from {@code pom-manipulation-common-lite} — so PNC/Repour can consume Ant Manipulator's
 * output exactly like PME's and GME's: a diff of the groupId:artifactId:version changes actually computed, not
 * our own richer pre-alignment discovery report (see {@link AlignmentReport} for that).
 *
 * <p>
 * Unlike a Maven reactor, an Ant build has no single canonical root GAV. {@code executionRoot} — which PME/Repour
 * use to compute "the project's" GAV change — is taken from whichever changed artifact has a finding sourced
 * directly from the root build file, falling back to the first changed artifact (by groupId:artifactId order) if
 * none is declared there.
 */
public final class PmeAlignmentReport {

    private PmeAlignmentReport() {
    }

    /**
     * @param rootBuildFile the build file passed to {@code -f}, used to pick the {@code executionRoot} artifact
     * @param correlated every artifact this run discovered, correlated by groupId:artifactId
     * @param reconciler resolves a tokenised version to its concrete value(s)
     * @param versionRewrites base version literal -&gt; computed publish version, as applied by the rewrite step
     * @return the populated report; {@code getModules()} is empty when nothing was realigned
     */
    public static PME build(
            File rootBuildFile,
            GavCorrelator.Result correlated,
            VersionReconciler reconciler,
            Map<String, String> versionRewrites) {

        PME report = new PME();
        ModulesItem executionRootModule = null;

        for (CorrelatedGav artifact : correlated.getArtifacts()) {
            Realignment realignment = realignmentOf(artifact, reconciler, versionRewrites);
            if (realignment == null) {
                continue;
            }
            String originalVersion = realignment.originalVersion;
            String newVersion = realignment.newVersion;

            ModulesItem module = new ModulesItem();
            module.getGav()
                    .setOriginalGAV(artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + originalVersion);
            module.getGav()
                    .setPVR(new SimpleProjectVersionRef(artifact.getGroupId(), artifact.getArtifactId(), newVersion));
            report.getModules().add(module);

            if (executionRootModule == null && isDeclaredIn(artifact, rootBuildFile)) {
                executionRootModule = module;
            }
        }

        if (executionRootModule == null && !report.getModules().isEmpty()) {
            executionRootModule = report.getModules().get(0);
        }
        if (executionRootModule != null) {
            report.getGav().setOriginalGAV(executionRootModule.getGav().getOriginalGAV());
            report.getGav().setPVR(executionRootModule.getGav().getPVR());
        }

        return report;
    }

    /** Write {@code report} to {@code file} using PME's own serialisation (pretty-printed, nulls/empties omitted). */
    public static File writeTo(PME report, File file) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(JSONUtils.jsonToString(report));
        }
        return file;
    }

    private static final class Realignment {
        private final String originalVersion;
        private final String newVersion;

        private Realignment(String originalVersion, String newVersion) {
            this.originalVersion = originalVersion;
            this.newVersion = newVersion;
        }
    }

    /**
     * Whether, and how, this artifact was realigned. Its primary version's concrete candidates are either
     * itself (already concrete) or every value a tokenised version reconciles to — a tokenised version can
     * reconcile <em>ambiguously</em> (e.g. a release vs. its {@code -SNAPSHOT} variant both matching the same
     * filter token), which is fine as long as every candidate was realigned to the <em>same</em> new version;
     * if they diverge we can't confidently report a single before-value, so the artifact is skipped, mirroring
     * PME's refusal to guess.
     */
    private static Realignment realignmentOf(
            CorrelatedGav artifact,
            VersionReconciler reconciler,
            Map<String, String> versionRewrites) {
        String primary = artifact.primaryVersion();
        if (primary == null) {
            return null;
        }
        Set<String> candidates = artifact.tokenizedVersions().contains(primary)
                ? reconciler.reconcile(primary).getResolved()
                : Collections.singleton(primary);
        if (candidates.isEmpty()) {
            return null; // unresolved
        }

        Set<String> newVersions = new LinkedHashSet<>();
        for (String candidate : candidates) {
            String newVersion = versionRewrites.get(candidate);
            if (newVersion != null) {
                newVersions.add(newVersion);
            }
        }
        if (newVersions.size() != 1) {
            return null; // not realigned, or candidates diverge on the realigned value
        }

        String newVersion = newVersions.iterator().next();
        // Deterministic, and prefers a release-looking candidate over e.g. its "-SNAPSHOT" sibling, since a
        // release version string is always a lexicographic prefix (and so the minimum) of that sibling.
        String originalVersion = new TreeSet<>(candidates).first();
        return newVersion.equals(originalVersion) ? null : new Realignment(originalVersion, newVersion);
    }

    private static boolean isDeclaredIn(CorrelatedGav artifact, File rootBuildFile) {
        File canonicalRoot = canonical(rootBuildFile);
        return artifact.getFindings()
                .stream()
                .map(ResolvedGav::getSourceFile)
                .filter(Objects::nonNull)
                .map(PmeAlignmentReport::canonical)
                .anyMatch(f -> f.equals(canonicalRoot));
    }

    private static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f.getAbsoluteFile();
        }
    }
}
