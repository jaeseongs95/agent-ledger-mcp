# SPDX-License-Identifier: AGPL-3.0-or-later

import base64
import contextlib
import io
import json
import os
import runpy
import sys
import tempfile
import unittest
import urllib.error
import urllib.parse
from email.message import Message
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
        self.enterContext(patch.object(badges, "readme_additions", return_value=[]))
        self.github = Mock()
        self.github.head.return_value = SHA
        self.github.publish.return_value = True
        self.sonar = badges.Sonar(
            urllib.parse.urlsplit("https://sonar.example.invalid"), "analysis-token", "read-token", PROJECT,
        )
        self.request = self.enterContext(patch.object(badges, "request", side_effect=self.response))
        self.latest = [{"key": "analysis", "revision": SHA}]
        self.gate = "OK"
        self.failure_metric = None

    def response(self, base, path, token, *, params, accept="application/json"):
        match path:
            case "api/ce/task":
                self.assertEqual(token, "analysis-token")
                return {"task": {"analysisId": "analysis", "status": "SUCCESS", "componentKey": PROJECT}}
            case "api/qualitygates/project_status":
                self.assertEqual(params, {"analysisId": "analysis"})
                return {"projectStatus": {"status": self.gate}}
            case "api/project_analyses/search":
                self.assertEqual(token, "read-token")
                return {"analyses": self.latest}
            case "api/project_badges/token":
                self.assertEqual(token, "read-token")
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

    def test_analysis_of_another_project_is_rejected(self):
        self.request.side_effect = None
        self.request.return_value = {"task": {"status": "SUCCESS", "componentKey": "different-project"}}
        with self.assertRaisesRegex(badges.BadgeError, "this project"):
            self.refresh()
        self.github.publish.assert_not_called()

    def test_stale_sonar_snapshot_skips_badge_download(self):
        self.latest = []
        self.refresh()
        self.github.publish.assert_not_called()
        self.assertEqual(self.request.call_count, 3)

    def test_empty_badge_token_fails_before_download(self):
        def response(*args, **kwargs):
            if args[1] == "api/project_badges/token":
                return {"token": ""}
            return self.response(*args, **kwargs)

        self.request.side_effect = response
        with self.assertRaisesRegex(badges.BadgeError, "did not return a project badge token"):
            self.refresh()
        self.github.publish.assert_not_called()

    def test_download_failure_preserves_existing_badges(self):
        path = self.checkout / "coverage.svg"
        path.write_bytes(b"old")
        self.failure_metric = "lines-of-code"
        with self.assertRaisesRegex(badges.BadgeError, "Download failed"):
            self.refresh()
        self.github.publish.assert_not_called()
        self.assertEqual(path.read_bytes(), b"old")
        self.assertEqual(list(self.staging.iterdir()), [self.checkout])

    def test_missing_browse_token_preserves_badges(self):
        self.sonar.read_token = ""
        with self.assertRaisesRegex(badges.BadgeError, "badge read credential"):
            self.refresh()
        self.github.publish.assert_not_called()

    def test_stale_branch_skips_download(self):
        self.github.head.return_value = "b" * 40
        self.refresh()
        self.request.assert_not_called()
        self.github.publish.assert_not_called()

    def test_branch_advance_during_publication_preserves_existing_badges(self):
        self.github.publish.return_value = False
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.refresh()
        self.assertIn("Existing badges preserved", output.getvalue())
        self.assertEqual(list(self.checkout.iterdir()), [])

    def test_analysis_changed_during_download(self):
        def response(*args, **kwargs):
            value = self.response(*args, **kwargs)
            if args[1] == "api/project_badges/measure" and kwargs["params"]["metric"] == "ncloc":
                self.latest = [{"key": "newer", "revision": "b" * 40}]
            return value

        self.request.side_effect = response
        self.refresh()
        self.github.publish.assert_not_called()

    def test_branch_and_analysis_id_are_both_checked(self):
        def response(*args, **kwargs):
            value = self.response(*args, **kwargs)
            if args[1] == "api/ce/task":
                value["task"]["branch"] = "retained"
            if args[1] in ("api/project_analyses/search", "api/project_badges/measure"):
                self.assertEqual(kwargs["params"]["branch"], "retained")
            return value

        self.request.side_effect = response
        self.refresh()
        self.github.publish.assert_called_once()


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
            good.replace(b"<svg ", b'<html ').replace(b"</svg>", b"</html>"),
            good.replace(b"<text>", b'<text fill="url(https://outside.invalid)">', 1),
            good.replace(b"</text>", b"</text>unexpected", 1),
            good.replace(b"91.3%", b"not a percentage"),
            b'<!DOCTYPE svg [<!ENTITY value "91.3%">]>' + good,
        ]
        for invalid in corruptions:
            with self.subTest(case=invalid), self.assertRaises(badges.BadgeError):
                badges.validate_svg("coverage", invalid, ("sonar.example.invalid", "badge-token"))

    def test_github_race_and_rejection(self):
        github = badges.GitHub("owner/repo", "release/v1.2.3", "actions-token")
        with patch.object(badges, "request", return_value={"errors": [{"message": "sensitive details"}]}) as request:
            with patch.object(github, "head", return_value="b" * 40):
                self.assertFalse(github.publish(SHA, []))
            self.assertEqual(request.call_args.kwargs["payload"]["variables"]["input"]["expectedHeadOid"], SHA)
            with patch.object(github, "head", return_value=SHA):
                with self.assertRaisesRegex(badges.BadgeError, "GitHub rejected the signed badge commit"):
                    github.publish(SHA, [])

    def test_github_ref_and_successful_update(self):
        github = badges.GitHub("owner/repo", "release/v1.2.3", "actions-token")
        with patch.object(badges, "request") as request:
            request.return_value = {"object": {"sha": SHA}}
            self.assertEqual(github.head(), SHA)
            request.assert_called_once_with(
                "https://api.github.com", "repos/owner/repo/git/ref/heads/release/v1.2.3", "actions-token",
            )
            request.return_value = {"data": {"createCommitOnBranch": {"commit": {"oid": "b" * 40}}}}
            self.assertTrue(github.publish(SHA, [{"path": "docs/badges/coverage.svg", "contents": "c3Zn"}]))

    def test_first_publication_adds_both_readmes_and_later_runs_preserve_them(self):
        directory = self.enterContext(tempfile.TemporaryDirectory())
        path = Path(directory) / "README.md"
        path.write_text("# Veridger\n\nBody\n", encoding="utf-8")
        labels = badges.README_LABELS["README.md"]
        with patch.object(badges, "README_LABELS", {str(path): labels}):
            additions = badges.readme_additions()
            self.assertEqual(len(additions), 1)
            content = base64.b64decode(additions[0]["contents"]).decode()
            self.assertTrue(content.startswith("# Veridger\n\n![Quality Gate](docs/badges/quality-gate.svg)\n"))
            self.assertEqual(content.count("](docs/badges/"), 7)
            self.assertTrue(content.endswith("\n\nBody\n"))
            path.write_text(content, encoding="utf-8")
            self.assertEqual(badges.readme_additions(), [])
            path.write_text("# Unexpected heading\n\nBody\n", encoding="utf-8")
            with self.assertRaisesRegex(badges.BadgeError, "beneath the README title"):
                badges.readme_additions()


class HttpRequestTest(unittest.TestCase):
    BASE = "https://sonar.example.invalid"

    def response(self, content_type, content):
        response = io.BytesIO(content)
        response.status = 200
        response.headers = Message()
        response.headers["Content-Type"] = content_type
        return response

    def test_json_post_encodes_payload_and_sends_authorization_header(self):
        response = self.response("application/json; charset=utf-8", b'{"ok":true}')
        with patch.object(badges.HTTP, "open", return_value=response) as open_request:
            self.assertEqual(
                badges.request(self.BASE, "api/task", "test-token", params={"id": "a/b"}, payload={"x": 1}),
                {"ok": True},
            )
        request = open_request.call_args.args[0]
        self.assertEqual(request.full_url, f"{self.BASE}/api/task?id=a%2Fb")
        self.assertEqual(request.get_header("Authorization"), "Bearer test-token")
        self.assertEqual(json.loads(request.data), {"x": 1})

    def test_svg_download_is_bounded(self):
        with patch.object(badges.HTTP, "open", return_value=self.response("image/svg+xml", b"<svg/>")):
            self.assertEqual(badges.request(self.BASE, "api/badge", None, accept="image/svg+xml"), b"<svg/>")
        response = self.response("image/svg+xml", b"x" * (badges.MAX_SVG_BYTES + 1))
        with patch.object(badges.HTTP, "open", return_value=response):
            with self.assertRaisesRegex(badges.BadgeError, "size limit"):
                badges.request(self.BASE, "api/badge", None, accept="image/svg+xml")

    def test_invalid_response_and_connection_errors_have_no_sensitive_details(self):
        with patch.object(badges.HTTP, "open", return_value=self.response("text/html", b"private body")):
            with self.assertRaisesRegex(badges.BadgeError, r"^api returned an unexpected response\.$"):
                badges.request(self.BASE, "api/task", "test-token")
        for failure, message in (
            (urllib.error.HTTPError(self.BASE, 403, "sensitive response", {}, None), "api request failed (HTTP 403)."),
            (urllib.error.URLError("sensitive URL"), "api request could not be completed."),
        ):
            with self.subTest(failure=type(failure)), patch.object(badges.HTTP, "open", side_effect=failure):
                with self.assertRaises(badges.BadgeError) as error:
                    badges.request(self.BASE, "api/task", "test-token")
                self.assertEqual(str(error.exception), message)

    def test_redirects_are_not_followed(self):
        handler = badges.NoRedirect()
        self.assertIsNone(handler.redirect_request(None, None, 302, "", {}, "https://other.invalid"))


class CliTest(unittest.TestCase):
    def setUp(self):
        self.env = {
            "SONAR_HOST_URL": "https://sonar.example.invalid:9443/base",
            "GITHUB_REF": "refs/heads/release/v1.2.3",
            "SONAR_PROJECT_KEY": PROJECT,
            "SONAR_TOKEN": "test-token",
            "SONAR_BADGE_READ_TOKEN": "read-token",
            "GITHUB_REPOSITORY": "owner/repo",
            "GH_TOKEN": "gh-token",
            "SONAR_CE_TASK_ID": "ce-task",
            "GITHUB_SHA": SHA,
            "RUNNER_TEMP": "temporary",
        }

    def test_masks_endpoint_and_hostname(self):
        with patch.dict(os.environ, self.env), patch.object(sys, "argv", ["script", "mask-host"]):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                badges.main()
        self.assertCountEqual(output.getvalue().splitlines(), (
            "::add-mask::sonar.example.invalid",
            "::add-mask::sonar.example.invalid:9443",
        ))

    def test_publish_selects_the_release_branch(self):
        with patch.dict(os.environ, self.env), patch.object(sys, "argv", ["script", "publish"]):
            with patch.object(badges, "refresh") as refresh:
                badges.main()
        sonar, github, task, sha, staging = refresh.call_args.args
        self.assertEqual((task, sha, staging), ("ce-task", SHA, "temporary"))
        self.assertEqual(sonar.project, PROJECT)
        self.assertEqual(github.branch, "release/v1.2.3")

    def test_invalid_arguments_and_configuration_exit_safely(self):
        cases = [
            (self.env, "invalid", "Expected mask-host or publish."),
            (self.env | {"GITHUB_REF": "refs/heads/develop"}, "publish", "Badge publication is limited to release branches."),
            (self.env | {"SONAR_HOST_URL": "http://private.invalid"}, "publish", "SonarQube requires a valid HTTPS endpoint."),
            ({}, "publish", "Badge publication stopped because required data was unavailable."),
        ]
        for env, action, message in cases:
            with self.subTest(message=message), patch.dict(os.environ, env, clear=True):
                with patch.object(sys, "argv", ["script", action]), self.assertRaises(SystemExit) as error:
                    runpy.run_path(badges.__file__, run_name="__main__")
                self.assertEqual(error.exception.code, message)
