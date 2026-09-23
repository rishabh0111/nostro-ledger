#!/usr/bin/env python3
"""Turn the test reports into the pipeline's claim summary.

The README claims a list of invariants and names the test that proves each;
``ReadmeClaimsTest`` keeps that table honest and writes it to ``target/claims.tsv``.
This reads that table alongside the surefire and failsafe XML reports and prints the
claims back as markdown, each ticked by the test that ran, or crossed with the reason it
did not. It exits non-zero if any claim is unproved, so a claim whose test was removed,
renamed or skipped fails the pipeline rather than quietly disappearing from the summary.

    claim-summary.py <claims.tsv> <report-root>...
"""

import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

PASSED, FAILED, SKIPPED, MISSING = "passed", "failed", "skipped", "missing"

MARK = {PASSED: "✅", FAILED: "❌", SKIPPED: "⚠️", MISSING: "❓"}
WORDING = {FAILED: "failed", SKIPPED: "skipped", MISSING: "did not run"}


def results(roots):
    """Every test case in the reports under the roots, by ``Class#method`` and by ``Class``."""
    by_test = {}
    for root in roots:
        for report in Path(root).rglob("TEST-*.xml"):
            for case in ElementTree.parse(report).getroot().iter("testcase"):
                simple_name = case.get("classname", "").rsplit(".", 1)[-1]
                if case.find("failure") is not None or case.find("error") is not None:
                    outcome = FAILED
                elif case.find("skipped") is not None:
                    outcome = SKIPPED
                else:
                    outcome = PASSED
                # A parameterized test reports each invocation as `method(Types)[n]`; the claim names
                # the method, which is proved only if every invocation of it passed.
                method = case.get("name").split("(", 1)[0].split("[", 1)[0]
                key = f"{simple_name}#{method}"
                by_test[key] = worst(by_test.get(key, PASSED), outcome)
                by_test[simple_name] = worst(by_test.get(simple_name, PASSED), outcome)
    return by_test


def worst(left, right):
    order = [PASSED, SKIPPED, MISSING, FAILED]
    return max(left, right, key=order.index)


def claims(table):
    for line in Path(table).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        text, _, references = line.partition("\t")
        yield text, references.split()


def main(argv):
    if len(argv) < 3:
        sys.exit(__doc__)

    # The marks and the claims themselves are not ASCII, whatever the platform's default encoding is.
    sys.stdout.reconfigure(encoding="utf-8")

    table, roots = argv[1], argv[2:]
    if not Path(table).is_file():
        # Said on stdout as well, because stdout is the run summary: a build that failed before the
        # claims were parsed should say that there, not leave the summary blank.
        reason = (f"## No claims were checked\n\nNo claim table at `{table}`: the build did not get "
                  f"as far as `ReadmeClaimsTest`, so nothing here is proved.")
        print(reason)
        print(reason, file=sys.stderr)
        sys.exit(1)

    by_test = results(roots)

    rows, unproved = [], []
    for text, references in claims(table):
        outcomes = {reference: by_test.get(reference, MISSING) for reference in references}
        outcome = PASSED
        for reference in references:
            outcome = worst(outcome, outcomes[reference])
        if outcome != PASSED:
            unproved += [f"{text} — {reference} {WORDING[state]}"
                         for reference, state in outcomes.items() if state != PASSED]
        proof = ", ".join(f"`{reference}` {MARK[state]}" if state != PASSED else f"`{reference}`"
                          for reference, state in outcomes.items())
        rows.append(f"| {MARK[outcome]} | {text} | {proof} |")

    print("## The invariants, and the test that proved each\n")
    print("| | Claim | Proof |")
    print("| --- | --- | --- |")
    print("\n".join(rows))

    if unproved:
        print("\n**Unproved claims:**\n")
        print("\n".join(f"- {line}" for line in unproved))
        sys.exit(1)
    print(f"\n{len(rows)} claims, each proved by a test that ran and passed.")


if __name__ == "__main__":
    main(sys.argv)
