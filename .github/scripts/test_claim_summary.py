#!/usr/bin/env python3
"""The summary script decides whether a claim counts as proved, so it has tests of its own.

    python3 -m unittest discover -s .github/scripts
"""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("claim-summary.py")

PASSING = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="io.nostro.api.schema.SchemaInvariantsIT">
  <testcase name="aCrossTenantReferenceIsRefusedByTheForeignKey" classname="io.nostro.api.schema.SchemaInvariantsIT"/>
  <testcase name="theFloorIsACheckConstraint" classname="io.nostro.api.schema.SchemaInvariantsIT"/>
</testsuite>
"""

FAILING = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="io.nostro.api.ledger.EntriesIT">
  <testcase name="anUnbalancedEntryIsRefused" classname="io.nostro.api.ledger.EntriesIT">
    <failure message="expected 422">boom</failure>
  </testcase>
</testsuite>
"""

SKIPPING = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="io.nostro.api.ledger.LedgerRefusalsTest">
  <testcase name="everyRefusalHasAResponse" classname="io.nostro.api.ledger.LedgerRefusalsTest"/>
  <testcase name="theCatalogIsClosed" classname="io.nostro.api.ledger.LedgerRefusalsTest">
    <skipped/>
  </testcase>
</testsuite>
"""


class ClaimSummary(unittest.TestCase):

    def run_summary(self, claims, reports):
        """Runs the script over a table and a set of reports, and returns (exit code, output)."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            table = root / "claims.tsv"
            table.write_text(claims, encoding="utf-8")
            report_dir = root / "surefire-reports"
            report_dir.mkdir()
            for name, xml in reports.items():
                (report_dir / f"TEST-{name}.xml").write_text(xml, encoding="utf-8")

            done = subprocess.run(
                [sys.executable, str(SCRIPT), str(table), str(report_dir)],
                capture_output=True, text=True, encoding="utf-8")
            return done.returncode, done.stdout

    def test_a_claim_whose_proofs_all_passed_is_ticked(self):
        code, out = self.run_summary(
            "A cross-tenant write is refused\tSchemaInvariantsIT#aCrossTenantReferenceIsRefusedByTheForeignKey\n",
            {"io.nostro.api.schema.SchemaInvariantsIT": PASSING})
        self.assertEqual(code, 0)
        self.assertIn("| ✅ | A cross-tenant write is refused |", out)
        self.assertIn("1 claims", out)

    def test_a_claim_is_only_as_good_as_its_weakest_proof(self):
        code, out = self.run_summary(
            "An unbalanced Entry is refused\tSchemaInvariantsIT#theFloorIsACheckConstraint EntriesIT#anUnbalancedEntryIsRefused\n",
            {"io.nostro.api.schema.SchemaInvariantsIT": PASSING,
             "io.nostro.api.ledger.EntriesIT": FAILING})
        self.assertEqual(code, 1)
        self.assertIn("| ❌ | An unbalanced Entry is refused |", out)
        self.assertIn("EntriesIT#anUnbalancedEntryIsRefused failed", out)

    def test_a_proof_that_did_not_run_leaves_the_claim_unproved(self):
        code, out = self.run_summary(
            "A renamed proof\tSchemaInvariantsIT#aTestThatNoLongerExists\n",
            {"io.nostro.api.schema.SchemaInvariantsIT": PASSING})
        self.assertEqual(code, 1)
        self.assertIn("aTestThatNoLongerExists did not run", out)

    def test_a_skipped_proof_leaves_the_claim_unproved(self):
        code, out = self.run_summary(
            "A skipped proof\tLedgerRefusalsTest#theCatalogIsClosed\n",
            {"io.nostro.api.ledger.LedgerRefusalsTest": SKIPPING})
        self.assertEqual(code, 1)
        self.assertIn("theCatalogIsClosed skipped", out)

    def test_a_parameterized_proof_is_ticked_only_when_every_invocation_passed(self):
        """A report names each invocation `method(Types)[n]`; the claim names the method, and needs all of them."""
        def invocations(*outcomes):
            cases = "".join(
                f'<testcase name="haltsItsPartition(String, Poison)[{n}]" classname="io.nostro.projection.EntryApplyIT">'
                + ("<failure/>" if outcome == "failed" else "") + "</testcase>"
                for n, outcome in enumerate(outcomes, start=1))
            return f'<testsuite name="io.nostro.projection.EntryApplyIT">{cases}</testsuite>'

        claim = "A poison message halts its partition\tEntryApplyIT#haltsItsPartition\n"

        code, out = self.run_summary(claim, {"io.nostro.projection.EntryApplyIT": invocations("passed", "passed")})
        self.assertEqual(code, 0, out)

        code, out = self.run_summary(claim, {"io.nostro.projection.EntryApplyIT": invocations("passed", "failed")})
        self.assertEqual(code, 1)
        self.assertIn("EntryApplyIT#haltsItsPartition failed", out)

    def test_a_class_named_as_the_proof_is_only_ticked_when_all_of_it_passed(self):
        """A claim proved by a whole class: one skipped test in it is enough to unprove the claim."""
        code, out = self.run_summary(
            "A whole class is the proof\tLedgerRefusalsTest\n",
            {"io.nostro.api.ledger.LedgerRefusalsTest": SKIPPING})
        self.assertEqual(code, 1)
        self.assertIn("LedgerRefusalsTest skipped", out)

        code, out = self.run_summary(
            "A whole class is the proof\tSchemaInvariantsIT\n",
            {"io.nostro.api.schema.SchemaInvariantsIT": PASSING})
        self.assertEqual(code, 0)

    def test_no_claim_table_says_so_in_the_summary_itself(self):
        """A build that failed before the claims were parsed leaves a reason, not a blank summary."""
        done = subprocess.run(
            [sys.executable, str(SCRIPT), "no-such-table.tsv", "."],
            capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(done.returncode, 1)
        self.assertIn("No claim table", done.stdout)
        self.assertIn("nothing here is proved", done.stdout)


if __name__ == "__main__":
    unittest.main()
