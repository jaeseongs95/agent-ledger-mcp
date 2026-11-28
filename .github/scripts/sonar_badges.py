# SPDX-License-Identifier: AGPL-3.0-or-later
"""Publish a verified SonarQube snapshot with a compare-and-swap branch update."""

import base64
import json
import os
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


BADGES = {
    "quality-gate": ("alert_status", "quality gate", r"passed"),
    "security": ("security_rating", "security", r"[A-E]"),
    "reliability": ("reliability_rating", "reliability", r"[A-E]"),
    "maintainability": ("sqale_rating", "maintainability", r"[A-E]"),
    "coverage": ("coverage", "coverage", r"\d+(?:\.\d+)?%"),
    "duplicated-lines": ("duplicated_lines_density", "duplicated lines", r"\d+(?:\.\d+)?%"),
    "lines-of-code": ("ncloc", "lines of code", r"\d+(?:\.\d+)?[kmbt]?"),
}
BADGE_DIR = Path("docs/badges")
README_LABELS = {
    "README.md": ("Quality Gate", "Security Rating", "Reliability Rating", "Maintainability Rating",
                  "Coverage", "Duplicated Lines", "Lines of Code"),
    "README.ko.md": ("품질 게이트", "보안 등급", "신뢰성 등급", "유지관리성 등급",
                     "커버리지", "중복 줄", "코드 줄 수"),
}
MAX_SVG_BYTES = 64 * 1024
SVG_NS = "{http://www.w3.org/2000/svg}"
SVG_TAGS = {"svg", "g", "linearGradient", "stop", "clipPath", "rect", "text", "path", "title", "desc", "defs"}
SVG_ATTRIBUTES = set(
    "role width height viewBox id x y x1 x2 y1 y2 offset stop-color stop-opacity rx ry "
    "fill fill-opacity clip-path font-family font-size text-anchor textLength d "
    "shape-rendering lengthAdjust".split()
)
URL_OR_CREDENTIAL = re.compile(
    r"https?://|(?:\d{1,3}\.){3}\d{1,3}|\b(?:sq[apub]_|gh[opsur]_|github_pat_)[a-z0-9_]+",
    re.IGNORECASE,
)
COMMIT = """
mutation ($input: CreateCommitOnBranchInput!) {
  createCommitOnBranch(input: $input) { commit { oid } }
}
"""


class BadgeError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


HTTP = urllib.request.build_opener(NoRedirect())


def request(base, path, token, *, params=None, accept="application/json", payload=None):
    url = f"{base}/{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    headers = {"Accept": accept, "Cache-Control": "no-cache", "User-Agent": "veridger-sonar-badges"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers)
    try:
        with HTTP.open(req, timeout=25) as response:
            if response.status != 200 or response.headers.get_content_type() != accept:
                raise BadgeError(f"{path.split('/')[0]} returned an unexpected response.")
            if accept == "image/svg+xml":
                body = response.read(MAX_SVG_BYTES + 1)
                if len(body) > MAX_SVG_BYTES:
                    raise BadgeError("An SVG exceeded the badge size limit.")
                return body
            return json.load(response)
    except urllib.error.HTTPError as exc:
        exc.close()
        raise BadgeError(f"{path.split('/')[0]} request failed (HTTP {exc.code}).") from None
    except (OSError, ValueError):
        raise BadgeError(f"{path.split('/')[0]} request could not be completed.") from None


def sonar_endpoint():
    endpoint = urllib.parse.urlsplit(os.environ["SONAR_HOST_URL"].strip())
    if (endpoint.scheme != "https" or not endpoint.hostname or endpoint.username is not None
            or endpoint.query or endpoint.fragment):
        raise BadgeError("SonarQube requires a valid HTTPS endpoint.")
    return endpoint


def validate_svg(name, content, secrets):
    try:
        text = content.decode("utf-8")
        markup = re.sub(r"^\s*<\?xml [^?]*\?>", "", text)
        raw = markup.replace("<!-- SONARQUBE MEASURE -->", "")
        raw = raw.replace('xmlns="http://www.w3.org/2000/svg"', "")
        if "<?" in raw or "<!" in raw:
            raise ValueError
        values = [raw, text[:len(text) - len(markup)]]
        svg = ET.fromstring(text)
        if svg.tag != SVG_NS + "svg":
            raise ValueError
        labels = set()
        for node in svg.iter():
            if not node.tag.startswith(SVG_NS) or node.tag[len(SVG_NS):] not in SVG_TAGS:
                raise ValueError
            if not node.attrib.keys() <= SVG_ATTRIBUTES:
                raise ValueError
            for attr in node.attrib.values():
                if "url(" in attr.lower() and not re.fullmatch(r"url\(#[a-zA-Z0-9_-]+\)", attr):
                    raise ValueError
                values.append(attr)
            if node.text and node.text.strip():
                labels.add(node.text.strip().lower())
                values.append(node.text)
            if node.tail and node.tail.strip():
                raise ValueError
        _, label, pattern = BADGES[name]
        allowed_values = labels - {label}
        if label not in labels or len(allowed_values) != 1:
            raise ValueError
        badge_value = next(iter(allowed_values))
        if not re.fullmatch(pattern, badge_value, re.IGNORECASE):
            raise ValueError
        for value in values:
            decoded = urllib.parse.unquote(value).lower()
            if URL_OR_CREDENTIAL.search(decoded) or any(secret.lower() in decoded for secret in secrets if secret):
                raise ValueError
    except (ValueError, ET.ParseError):
        raise BadgeError(f"Invalid or sensitive content in {name}.svg.") from None
    return badge_value


def measure_value(metric, value):
    if metric == "alert_status":
        return {"OK": "passed"}[value]
    if metric.endswith("_rating"):
        return {1: "a", 2: "b", 3: "c", 4: "d", 5: "e"}[float(value)]
    if metric != "ncloc":
        return f"{float(value):.1f}".removesuffix(".0") + "%"
    # Match SonarQube's abbreviated counts and its four-character cutoff for fractions.
    count = int(value)
    scale = (len(str(count)) - 1) // 3
    number = f"{count / 1000**scale:.1f}".removesuffix(".0")
    suffix = " kmbt"[scale]
    return ((number if len(number + suffix) <= 4 else number.partition(".")[0]) + suffix).strip()


class Sonar:
    def __init__(self, endpoint, analysis_token, read_token, project):
        self.endpoint = endpoint
        self.base = endpoint.geturl().rstrip("/")
        self.analysis_token = analysis_token
        self.read_token = read_token
        self.project = project

    def get(self, path, *, read=False, **params):
        return request(self.base, path, self.read_token if read else self.analysis_token, params=params)

    def download(self, task_id, sha, directory):
        task = self.get("api/ce/task", id=task_id)["task"]
        analysis_id = task.get("analysisId")
        if (task.get("status") != "SUCCESS" or task.get("componentKey") != self.project
                or task.get("id") != task_id or task.get("type") != "REPORT" or not analysis_id):
            raise BadgeError("SonarQube task did not successfully analyze this project.")
        status = self.get("api/qualitygates/project_status", analysisId=analysis_id)["projectStatus"]["status"]
        if status != "OK":
            raise BadgeError("The analyzed commit did not pass the Quality Gate.")
        project = {"project": self.project}
        if task.get("branch"):
            project["branch"] = task["branch"]
        if not self.read_token:
            raise BadgeError("The release badge read credential is missing.")
        if not self.is_latest(project, analysis_id, sha):
            return False
        measures = self.badge_measures(task.get("branch"))
        badge_token = self.get("api/project_badges/token", read=True, project=self.project)["token"]
        if not badge_token:
            raise BadgeError("SonarQube did not return a project badge token.")
        sensitive = (self.endpoint.hostname, self.endpoint.netloc, self.analysis_token, self.read_token, badge_token)
        for name, (metric, _, _) in BADGES.items():
            content = request(
                self.base, "api/project_badges/measure", None, accept="image/svg+xml",
                params={**project, "metric": metric, "token": badge_token},
            )
            if validate_svg(name, content, sensitive) != measures[metric]:
                raise BadgeError(f"{name}.svg does not match the analysis measures.")
            (directory / f"{name}.svg").write_bytes(content)
        # Neither the measures nor the SVG endpoints accept an analysis ID.
        return self.is_latest(project, analysis_id, sha)

    def badge_measures(self, branch):
        metrics = [metric for metric, _, _ in BADGES.values()]
        params = {"component": self.project, "metricKeys": ",".join(metrics)}
        if branch:
            params["branch"] = branch
        component = self.get("api/measures/component", read=True, **params)["component"]
        try:
            values = {measure["metric"]: measure["value"] for measure in component["measures"]}
            return {metric: measure_value(metric, values[metric]) for metric in metrics}
        except (IndexError, KeyError, TypeError, ValueError):
            raise BadgeError("SonarQube did not return all required analysis measures.") from None

    def is_latest(self, project, analysis_id, sha):
        analyses = self.get("api/project_analyses/search", read=True, **project, ps=1)["analyses"]
        if not analyses or analyses[0]["key"] != analysis_id or analyses[0].get("revision") != sha:
            return False
        # Live measures can change while another Compute Engine task is still processing.
        state = self.get("api/ce/component", read=True, component=self.project)
        current = state.get("current", {})
        return not state.get("queue") and current.get("analysisId") == analysis_id and current.get("status") == "SUCCESS"


class GitHub:
    def __init__(self, repository, branch, token):
        self.repository = repository
        self.branch = branch
        self.token = token
        self.base = "https://api.github.com"

    def head(self):
        ref = urllib.parse.quote(self.branch, safe="/")
        return request(self.base, f"repos/{self.repository}/git/ref/heads/{ref}", self.token)["object"]["sha"]

    def publish(self, sha, additions):
        payload = {
            "query": COMMIT,
            "variables": {"input": {
                "branch": {"repositoryNameWithOwner": self.repository, "branchName": self.branch},
                "message": {"headline": "chore(docs): refresh SonarQube quality badges"},
                "expectedHeadOid": sha,
                "fileChanges": {"additions": additions},
            }},
        }
        response = request(self.base, "graphql", self.token, payload=payload)
        if response.get("errors"):
            if self.head() != sha:
                return False
            raise BadgeError("GitHub rejected the signed badge commit. Check token permissions and branch rules.")
        oid = response["data"]["createCommitOnBranch"]["commit"]["oid"]
        print(f"Published SonarQube badges in {oid}.")
        return True


def file_addition(path, content):
    return {"path": path.as_posix(), "contents": base64.b64encode(content).decode("ascii")}


def readme_additions():
    additions = []
    for filename, labels in README_LABELS.items():
        path = Path(filename)
        title, _, rest = path.read_text(encoding="utf-8").partition("\n")
        lines = [
            f"![{label}]({(BADGE_DIR / (name + '.svg')).as_posix()})"
            for name, label in zip(BADGES, labels, strict=True)
        ]
        section = "\n" + "\n".join(lines) + "\n"
        if rest.startswith(section):
            continue
        if title != "# Veridger" or "docs/badges/" in rest:
            raise BadgeError("Could not install badge references beneath the README title.")
        additions.append(file_addition(path, (title + "\n" + section + rest).encode()))
    return additions


def refresh(sonar, github, task_id, sha, runner_temp):
    if github.head() != sha:
        print("Branch advanced after analysis. Existing badges preserved.")
        return
    with tempfile.TemporaryDirectory(prefix="sonar-badges-", dir=runner_temp) as staging:
        directory = Path(staging)
        if not sonar.download(task_id, sha, directory):
            print("SonarQube could not confirm the snapshot is current and idle. Existing badges preserved.")
            return
        additions = []
        for name in BADGES:
            path = BADGE_DIR / f"{name}.svg"
            content = (directory / path.name).read_bytes()
            if not path.exists() or path.read_bytes() != content:
                additions.append(file_addition(path, content))
        if additions:
            if not github.publish(sha, additions + readme_additions()):
                print("Branch advanced before publication. Existing badges preserved.")
        else:
            print("All seven SonarQube badges are unchanged.")


def main():
    endpoint = sonar_endpoint()
    if sys.argv[1:] == ["mask-host"]:
        for value in {endpoint.hostname, endpoint.netloc}:
            print(f"::add-mask::{value}")
        return
    if sys.argv[1:] != ["publish"]:
        raise BadgeError("Expected mask-host or publish.")
    ref = os.environ["GITHUB_REF"]
    if not ref.startswith("refs/heads/release/"):
        raise BadgeError("Badge publication is limited to release branches.")
    project = os.environ["SONAR_PROJECT_KEY"]
    sonar = Sonar(endpoint, os.environ["SONAR_TOKEN"], os.environ.get("SONAR_BADGE_READ_TOKEN"), project)
    github = GitHub(os.environ["GITHUB_REPOSITORY"], ref.removeprefix("refs/heads/"), os.environ["GH_TOKEN"])
    refresh(sonar, github, os.environ["SONAR_CE_TASK_ID"], os.environ["GITHUB_SHA"], os.environ["RUNNER_TEMP"])


if __name__ == "__main__":
    try:
        main()
    except BadgeError as error:
        sys.exit(str(error))
    except (KeyError, TypeError, ValueError, OSError):
        # Do not include response bodies, exception URLs or credential values in CI logs.
        sys.exit("Badge publication stopped because required data was unavailable.")
