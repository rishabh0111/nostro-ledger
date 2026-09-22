package io.nostro.api.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The README's first screen claims a list of invariants and names the test that proves each. CI
 * republishes that table as its job summary, ticked from the test reports, so the claims are the
 * pipeline's evidence rather than the README's assertion.
 *
 * <p>This test keeps the table honest — every proof resolves to a file that exists and a method that
 * is really declared there — and writes the parsed table to {@code target/claims.tsv}, which the
 * summary step reads. The markdown is parsed here and only here; the workflow's script joins that
 * table to the test reports and reads no markdown of its own.
 */
class ReadmeClaimsTest {

    /** A markdown link whose text is code: {@code [`SomeIT.someMethod`](path/to/SomeIT.java)}. */
    private static final Pattern PROOF = Pattern.compile("\\[`([^`]+)`]\\(([^)]+)\\)");

    /** The proof is a test reference when its text reads {@code Class.method}. */
    private static final Pattern TEST_REFERENCE = Pattern.compile("([A-Z]\\w+)\\.(\\w+)");

    private static final Path ROOT = repositoryRoot();

    record Claim(String text, List<Proof> proofs) {
    }

    record Proof(String reference, Path file) {

        /** A proof CI can tick: a test source, whose result the reports carry. Production code is cited, not run. */
        boolean isTest() {
            return file.toString().replace('\\', '/').contains("src/test/java");
        }

        /** The class named, whether the reference is {@code Class.method} or the whole {@code Class}. */
        String className() {
            var named = TEST_REFERENCE.matcher(reference);
            return named.matches() ? named.group(1) : reference;
        }

        /** The method named, or null when the whole class is the proof. */
        String methodName() {
            var named = TEST_REFERENCE.matcher(reference);
            return named.matches() ? named.group(2) : null;
        }

        /** {@code Class#method} for a named method, {@code Class} when the whole class is the proof. */
        String reportKey() {
            return methodName() == null ? className() : className() + "#" + methodName();
        }
    }

    @Test
    @DisplayName("every claim in the README names a proof the test reports will carry")
    void everyClaimNamesATestAsItsProof() {
        var claims = claims();
        // A floor, not the count: the table grows, and a parser that silently matched one row or none
        // would otherwise pass this test.
        assertThat(claims).hasSizeGreaterThanOrEqualTo(10);
        assertThat(claims).allSatisfy(claim -> assertThat(claim.proofs())
                .as("proofs for: %s", claim.text())
                .anyMatch(Proof::isTest));
    }

    @Test
    @DisplayName("every proof the README links to is a file that exists")
    void everyProofResolves() {
        assertThat(claims()).flatExtracting(Claim::proofs).allSatisfy(proof ->
                assertThat(ROOT.resolve(proof.file())).as("proof %s", proof.reference()).isRegularFile());
    }

    @Test
    @DisplayName("every proof names the class it links to, and the method it names is declared there")
    void everyTestReferenceIsRealCode() {
        for (Claim claim : claims()) {
            for (Proof proof : claim.proofs()) {
                var file = ROOT.resolve(proof.file());

                assertThat(file.getFileName()).asString()
                        .as("%s should live in %s.java", proof.reference(), proof.className())
                        .isEqualTo(proof.className() + ".java");

                if (proof.methodName() != null) {
                    assertThat(read(file))
                            .as("%s should declare %s()", proof.reference(), proof.methodName())
                            .contains("void " + proof.methodName() + "(");
                }
            }
        }
    }

    @Test
    @DisplayName("the parsed table is written where the pipeline's summary step reads it")
    void theTableIsPublishedForTheSummary() throws IOException {
        var lines = new ArrayList<String>();
        for (Claim claim : claims()) {
            var references = claim.proofs().stream()
                    .filter(Proof::isTest)
                    .map(Proof::reportKey)
                    .toList();
            lines.add(claim.text() + "\t" + String.join(" ", references));
        }

        var published = Path.of("target", "claims.tsv");
        Files.createDirectories(published.getParent());
        Files.write(published, lines, StandardCharsets.UTF_8);

        // Every row is a claim and at least one proof, tab-separated: what the summary step parses.
        assertThat(Files.readAllLines(published, StandardCharsets.UTF_8))
                .hasSameSizeAs(claims())
                .allSatisfy(line -> assertThat(line.split("\t")).hasSize(2)
                        .allSatisfy(field -> assertThat(field).isNotBlank()));
    }

    /** The rows of the README's "The invariants, and the test that proves each" table. */
    private static List<Claim> claims() {
        var claims = new ArrayList<Claim>();
        boolean inTable = false;
        for (String line : read(ROOT.resolve("README.md")).lines().toList()) {
            if (line.startsWith("## ")) {
                inTable = line.startsWith("## The invariants");
                continue;
            }
            if (!inTable || !line.startsWith("|")) {
                continue;
            }
            var cells = line.split("\\|", -1);
            if (cells.length < 4 || cells[1].isBlank() || cells[1].strip().startsWith("---")
                    || cells[1].strip().equals("Claim")) {
                continue;
            }
            var proofs = new ArrayList<Proof>();
            var links = PROOF.matcher(cells[2]);
            while (links.find()) {
                proofs.add(new Proof(links.group(1), Path.of(links.group(2))));
            }
            claims.add(new Claim(cells[1].strip(), List.copyOf(proofs)));
        }
        return List.copyOf(claims);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The module runs from its own directory; the README is at the root of the repository above it. */
    private static Path repositoryRoot() {
        for (Path here = Path.of("").toAbsolutePath(); here != null; here = here.getParent()) {
            // .git is a directory in a clone and a file in a worktree; either marks the root.
            if (Files.isRegularFile(here.resolve("README.md")) && Files.exists(here.resolve(".git"))) {
                return here;
            }
        }
        throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
    }
}
