"""Tests for the generated Grafana dashboard.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("build_dashboard", ROOT / "scripts" / "build_dashboard.py")
build_dashboard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build_dashboard)


class DashboardTest(unittest.TestCase):
    """The committed dashboard is exactly what the generator produces, and its layout is sound.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """

    def test_committed_json_matches_the_generator(self):
        """Editing the JSON by hand, or the generator without regenerating, fails here.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        self.assertEqual(build_dashboard.OUTPUT.read_text(encoding="utf-8"), build_dashboard.render(),
                         "run `make dashboard` and commit the result")

    def test_panels_fit_the_grid_without_overlapping(self):
        """No panel leaves the 24-column grid or covers another.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        taken = set()
        for panel in build_dashboard.build()["panels"]:
            position = panel["gridPos"]
            self.assertLessEqual(position["x"] + position["w"], 24, panel["title"])
            for x in range(position["x"], position["x"] + position["w"]):
                for y in range(position["y"], position["y"] + position["h"]):
                    self.assertNotIn((x, y), taken, f"{panel['title']} overlaps another panel")
                    taken.add((x, y))

    def test_every_panel_explains_itself(self):
        """Each panel other than a row has a description, shown as its tooltip.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        for panel in build_dashboard.build()["panels"]:
            if panel["type"] != "row":
                self.assertTrue(panel.get("description"), f"{panel['title']} has no description")

    def test_identifiers_are_unique_and_the_json_is_valid(self):
        """Panel IDs are unique and the file parses as JSON.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        dashboard = json.loads(build_dashboard.OUTPUT.read_text(encoding="utf-8"))
        ids = [panel["id"] for panel in dashboard["panels"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(dashboard["uid"], "mercadillo-overview")


if __name__ == "__main__":
    unittest.main()
