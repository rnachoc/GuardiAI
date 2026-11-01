"""Checks that keep the repository honest about its own rules.

Each test enforces a principle from the brief or a decision record, so breaking one fails the
build instead of relying on a reviewer to notice. Standard library only: `make test` runs this
in a plain Python container, with no dependencies to install.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

COMPONENTS = ["mercadillo", "chaos", "mcp", "agents", "policy", "console", "evals", "docs", "deploy"]
STATUSES = {"Done", "In progress", "Not started"}
SECRET_KEY = re.compile(r"(KEY|SECRET|TOKEN|PASSWORD|CREDENTIAL)", re.IGNORECASE)
DIGEST = re.compile(r"@sha256:[0-9a-f]{64}$")
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")


def read(relative: str) -> str:
    """Returns the text of a file, given its path from the repository root.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    return (ROOT / relative).read_text(encoding="utf-8")


def table_rows(markdown: str, heading: str) -> list[list[str]]:
    """Returns the body rows of the first table under a heading, as lists of cell texts.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    section = markdown.split(heading, 1)
    if len(section) < 2:
        raise AssertionError(f"heading {heading!r} not found")
    rows = []
    started = False
    for line in section[1].splitlines():
        stripped = line.strip()
        if stripped.startswith("|"):
            started = True
            cells = [cell.strip() for cell in stripped.strip("|").split("|")]
            if all(re.fullmatch(r":?-+:?", cell) for cell in cells):
                continue
            rows.append(cells)
        elif started:
            break
    return rows[1:]  # drop the header row


class EnvironmentExampleTest(unittest.TestCase):
    """Principle 4: .env.example contains local-only placeholders, never a credential.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """

    def test_secret_like_keys_are_empty_or_marked_local_only(self):
        """A key that looks like a secret holds nothing, or a value marked local-only.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        for number, line in enumerate(read(".env.example").splitlines(), start=1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            key, separator, value = line.partition("=")
            self.assertEqual(separator, "=", f"line {number} is not KEY=value")
            if SECRET_KEY.search(key):
                self.assertTrue(
                    value == "" or "local-only" in value,
                    f"{key} must be empty or a 'local-only' placeholder, found a value",
                )

    def test_runs_without_an_api_key_by_default(self):
        """A fresh copy uses the fake provider, has no API key and has a positive spend cap.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        values = dict(
            line.split("=", 1)
            for line in read(".env.example").splitlines()
            if line.strip() and not line.startswith("#")
        )
        self.assertEqual(values.get("GUARDIA_MODEL_PROVIDER"), "fake")
        self.assertEqual(values.get("ANTHROPIC_API_KEY"), "")
        self.assertGreater(float(values["GUARDIA_MAX_SPEND_USD"]), 0)

    def test_env_files_are_ignored_except_the_example(self):
        """Every .env file stays out of git, except the example.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        ignored = read(".gitignore").splitlines()
        self.assertIn(".env", ignored)
        self.assertIn("!.env.example", ignored)


class PinningTest(unittest.TestCase):
    """ADR 3: everything that runs is pinned, so a build means the same thing on every machine.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """

    def test_compose_images_are_pinned_by_digest(self):
        """Each service image in compose.yaml is pinned by digest.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        images = re.findall(r"^\s*image:\s*(\S+)\s*$", read("compose.yaml"), re.MULTILINE)
        self.assertTrue(images, "compose.yaml names no image")
        for image in images:
            self.assertRegex(image, DIGEST, f"{image} is not pinned by digest")

    def test_tool_images_are_pinned_by_digest(self):
        """Each tool image in the Makefile is pinned by digest.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        images = re.findall(r"^[A-Z_]+_IMAGE := (\S+)$", read("Makefile"), re.MULTILINE)
        self.assertGreaterEqual(len(images), 6)
        for image in images:
            self.assertRegex(image, DIGEST, f"{image} is not pinned by digest")

    def test_actions_are_pinned_to_a_full_commit(self):
        """Each GitHub Action is pinned to a full commit SHA.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        workflows = sorted((ROOT / ".github" / "workflows").glob("*.yml"))
        self.assertTrue(workflows)
        for workflow in workflows:
            for reference in re.findall(r"uses:\s*([^\s#]+)", workflow.read_text(encoding="utf-8")):
                if reference.startswith("./"):
                    continue
                _, _, version = reference.partition("@")
                self.assertRegex(version, FULL_SHA, f"{workflow.name}: {reference} is not pinned to a commit")


class DecisionRecordsTest(unittest.TestCase):
    """Principle 7: every significant decision is a numbered record with its context and trade-offs.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """

    def setUp(self):
        """Collects the decision records, in file name order.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        self.records = sorted((ROOT / "docs" / "adr").glob("[0-9][0-9][0-9][0-9]-*.md"))

    def test_records_are_numbered_without_gaps(self):
        """Records are numbered from 1 with no gap.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        numbers = [int(record.name[:4]) for record in self.records]
        self.assertGreaterEqual(len(numbers), 3)
        self.assertEqual(numbers, list(range(1, len(numbers) + 1)))

    def test_each_record_has_the_required_sections(self):
        """Each record has its number, status, date, context, decision and consequences.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        for record in self.records:
            text = record.read_text(encoding="utf-8")
            number = int(record.name[:4])
            with self.subTest(record=record.name):
                self.assertTrue(text.startswith(f"# {number}. "), "title must start with its number")
                self.assertRegex(text, r"\*\*Status:\*\* (Proposed|Accepted|Superseded by ADR \d+)")
                self.assertRegex(text, r"\*\*Date:\*\* \d{4}-\d{2}-\d{2}")
                for section in ("## Context", "## Decision", "## Consequences"):
                    self.assertIn(section, text)

    def test_the_index_lists_every_record(self):
        """The index links every record.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        index = read("docs/adr/README.md")
        for record in self.records:
            self.assertIn(f"({record.name})", index, f"{record.name} is missing from the index")


class StatusTest(unittest.TestCase):
    """Principle 5: the README and PROGRESS.md tell the same story about what exists.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """

    def test_progress_and_readme_agree_on_every_phase(self):
        """PROGRESS.md and the README give every phase the same status.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        progress = {row[0]: row[2] for row in table_rows(read("PROGRESS.md"), "## Phases")}
        readme = {row[0]: row[2] for row in table_rows(read("README.md"), "## Status")}
        self.assertEqual(sorted(progress, key=int), [str(phase) for phase in range(11)])
        self.assertEqual(progress, readme)
        for phase, status in progress.items():
            self.assertIn(status, STATUSES, f"phase {phase} has an unknown status {status!r}")

    def test_phases_are_finished_in_order(self):
        """No phase is done while an earlier one is not.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        statuses = [row[2] for row in table_rows(read("PROGRESS.md"), "## Phases")]
        finished = [status == "Done" for status in statuses]
        self.assertEqual(finished, sorted(finished, reverse=True), "a phase is done before an earlier one")


class LayoutTest(unittest.TestCase):
    """The monorepo layout from the brief, with a note in each folder saying what it holds today.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """

    def test_every_component_has_a_folder_with_a_readme(self):
        """Each component of the layout has a folder with a README.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        for component in COMPONENTS:
            self.assertTrue((ROOT / component / "README.md").is_file(), f"{component}/README.md is missing")

    def test_the_essentials_exist(self):
        """The licence, editor rules, Makefile, Compose file, progress and README exist.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        for name in ("LICENSE", ".editorconfig", "Makefile", "compose.yaml", "PROGRESS.md", "README.md"):
            self.assertTrue((ROOT / name).is_file(), f"{name} is missing")
        self.assertIn("MIT License", read("LICENSE"))


if __name__ == "__main__":
    unittest.main()
