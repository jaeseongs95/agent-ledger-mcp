# SPDX-License-Identifier: AGPL-3.0-or-later

import base64
import tempfile
import unittest
import urllib.parse
from pathlib import Path
from unittest.mock import Mock, patch

import sonar_badges as badges


VALUES = ("passed", "A", "B", "C", "91.3%", "0.0%", "28k")
SHA = "a" * 40
PROJECT = "veridger-release"


def svg(name):
    index = list(badges.BADGES).index(name)
    label = badges.BADGES[name][1]
    return f'<svg xmlns="http://www.w3.org/2000/svg"><text>{label}</text><text>{VALUES[index]}</text></svg>'.encode()


class PublicationTest(unittest.TestCase):
    def setUp(self):
        temporary = self.enterContext(tempfile.TemporaryDirectory())
        self.checkout = Path(temporary) / "badges"
        self.checkout.mkdir()
        self.staging = Path(temporary)
        self.enterContext(patch.object(badges, "BADGE_DIR", self.checkout))
        self.github = Mock()
        self.github.head.return_value = SHA
        self.sonar = badges.Sonar(urllib.parse.urlsplit("https://sonar.example.invalid"), "analysis-token", PROJECT)
        self.request = self.enterContext(patch.object(badges, "request", side_effect=self.response))
        self.latest = [{"key": "analysis", "revision": SHA}]
        self.gate = "OK"
        self.failure_metric = None

    def response(self, base, path, token, *, params, accept="application/json"):
        match path:
            case "api/ce/task":
                return {"task": {"analysisId": "analysis", "status": "SUCCESS", "componentKey": PROJECT}}
            case "api/qualitygates/project_status":
                self.assertEqual(params, {"analysisId": "analysis"})
                return {"projectStatus": {"status": self.gate}}
            case "api/project_analyses/search":
                return {"analyses": self.latest}
            case "api/project_badges/token":
                return {"token": "badge-token"}
            case "api/project_badges/measure":
                self.assertEqual(accept, "image/svg+xml")
                self.assertEqual(params["token"], "badge-token")
                name = next(name for name, metric in badges.BADGES.items() if metric[0] == params["metric"])
                if name == self.failure_metric:
                    raise badges.BadgeError("Download failed.")
                return svg(name)
            case _:
                self.fail("Unexpected endpoint")

    def refresh(self):
        badges.refresh(self.sonar, self.github, "task", SHA, self.staging)

    def test_all_seven_badges_in_one_commit(self):
        self.refresh()
        self.github.publish.assert_called_once()
        parent, additions = self.github.publish.call_args.args
        self.assertEqual(parent, SHA)
        self.assertEqual(len(additions), 7)
        for name, addition in zip(badges.BADGES, additions, strict=True):
            self.assertEqual(Path(addition["path"]).name, f"{name}.svg")
            self.assertEqual(base64.b64decode(addition["contents"]), svg(name))
        self.assertEqual(list(self.checkout.iterdir()), [])

    def test_only_changed_badge_is_published(self):
        for name in badges.BADGES:
            (self.checkout / f"{name}.svg").write_bytes(svg(name))
        (self.checkout / "coverage.svg").write_bytes(b"old")
        self.refresh()
        _, additions = self.github.publish.call_args.args
        self.assertEqual([Path(a["path"]).name for a in additions], ["coverage.svg"])

    def test_no_change_makes_no_commit(self):
        for name in badges.BADGES:
            (self.checkout / f"{name}.svg").write_bytes(svg(name))
        self.refresh()
        self.github.publish.assert_not_called()

    def test_gate_failure_preserves_badges(self):
        path = self.checkout / "coverage.svg"
        path.write_bytes(b"old")
        self.gate = "ERROR"
        with self.assertRaisesRegex(badges.BadgeError, "Quality Gate"):
            self.refresh()
        self.github.publish.assert_not_called()
        self.assertEqual(path.read_bytes(), b"old")
        self.assertEqual(self.request.call_count, 2)

    def test_download_failure_preserves_existing_badges(self):
        path = self.checkout / "coverage.svg"
        path.write_bytes(b"old")
        self.failure_metric = "lines-of-code"
        with self.assertRaisesRegex(badges.BadgeError, "Download failed"):
            self.refresh()
        self.github.publish.assert_not_called()
        self.assertEqual(path.read_bytes(), b"old")
        self.assertEqual(list(self.staging.iterdir()), [self.checkout])

    def test_stale_branch_skips_download(self):
        self.github.head.return_value = "b" * 40
        self.refresh()
        self.request.assert_not_called()
        self.github.publish.assert_not_called()

    def test_analysis_changed_during_download(self):
        def response(*args, **kwargs):
            value = self.response(*args, **kwargs)
            if args[1] == "api/project_badges/measure" and kwargs["params"]["metric"] == "ncloc":
                self.latest = [{"key": "newer", "revision": "b" * 40}]
            return value

        self.request.side_effect = response
        self.refresh()
        self.github.publish.assert_not_called()


class ValidationTest(unittest.TestCase):
    def test_badge_error_active_content_and_credentials(self):
        good = svg("coverage")
        corruptions = [
            b'<svg xmlns="http://www.w3.org/2000/svg"><!-- ERROR --><text>Project not found</text></svg>',
            good.replace(b"<text>", b'<a href="https://outside.invalid"/><text>', 1),
            good.replace(b"<text>", b'<script/><text>', 1),
            good.replace(b"<text>", b'<text data-url="sonar.example.invalid">', 1),
            good.replace(b"<text>", b'<text id="sonar&#46;example&#46;invalid">', 1),
            good.replace(b"<text>", b'<text id="badge-token">', 1),
            b'<!DOCTYPE svg [<!ENTITY value "91.3%">]>' + good,
        ]
        for invalid in corruptions:
            with self.subTest(case=invalid), self.assertRaises(badges.BadgeError):
                badges.validate_svg("coverage", invalid, ("sonar.example.invalid", "badge-token"))

    def test_github_race_and_rejection(self):
        github = badges.GitHub("owner/repo", "release/v1.2.3", "actions-token")
        with patch.object(badges, "request", return_value={"errors": [{"message": "sensitive details"}]}) as request:
            with patch.object(github, "head", return_value="b" * 40):
                github.publish(SHA, [])
            self.assertEqual(request.call_args.kwargs["payload"]["variables"]["input"]["expectedHeadOid"], SHA)
            with patch.object(github, "head", return_value=SHA):
                with self.assertRaisesRegex(badges.BadgeError, "GitHub rejected the signed badge commit"):
                    github.publish(SHA, [])
