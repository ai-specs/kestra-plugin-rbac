"""Require explicit declaration-upgrade PostgreSQL evidence, including every named case."""
from pathlib import Path
import json
import sys
import xml.etree.ElementTree as ET

CLASS = "io.kestra.extensions.rbac.applicationgroup.ApplicationPermissionUpgradeTest"
REQUIRED = [
    "upgradePreservesPolicySupportAndSubjectGrants",
    "conflictingMappingIsRejectedWithoutSideEffects",
    "failureAfterWritesRollsBackDirectoryDeclarationsEpochAndReceipt",
    "reviewedUpgradeIsIdempotentButCannotOverwriteLaterWrites",
    "disabledRuntimePolicyRemainsDisabledWithoutChangingSupport",
    "unknownReferencesAndUnknownMappingsAreRejected",
    "approvedFalseCannotGainSupportDuringUpgrade",
    "sourceDriftAndWrongApprovalCannotApply",
]


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: check-declaration-upgrade-tests.py <postgres-results>")
    path = Path(sys.argv[1]) / ("TEST-" + CLASS + ".xml")
    if not path.is_file():
        raise SystemExit("FAIL: missing declaration-upgrade PostgreSQL XML")
    suite = ET.parse(path).getroot()
    cases = suite.findall("testcase")
    names = [case.attrib["name"].removesuffix("()") for case in cases]
    if suite.get("name") != CLASS or len(names) != len(set(names)):
        raise SystemExit("FAIL: unexpected suite or duplicate case")
    for name in REQUIRED:
        if name not in names:
            raise SystemExit("FAIL: missing required declaration-upgrade case " + name)
    counts = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
    if counts["tests"] != len(cases) or any(counts[key] for key in ("failures", "errors", "skipped")):
        raise SystemExit("FAIL: declaration-upgrade failed, skipped, or report count inconsistent")
    if any(case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped")):
        raise SystemExit("FAIL: declaration-upgrade case did not pass")
    output = "\n".join(suite.itertext())
    if output.count("Declaration upgrade backend: PostgreSQL") != len(cases) or "Declaration upgrade backend: H2" in output:
        raise SystemExit("FAIL: every declaration-upgrade case must prove a real PostgreSQL connection")
    print("PASS: declaration-upgrade PostgreSQL cases:", len(cases), "zero failure/error/skip")
    print(json.dumps({"profile": "declaration-upgrade", "backend": "PostgreSQL", **counts, "requiredCases": REQUIRED}))


if __name__ == "__main__":
    main()
