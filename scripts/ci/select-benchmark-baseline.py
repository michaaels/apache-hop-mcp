"""Select the newest published stable ancestor release for a benchmark candidate."""
import argparse
import json
import re
import subprocess

STABLE = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
MINIMUM = (2, 2, 2)


def version(tag):
    match = STABLE.fullmatch(tag)
    return tuple(map(int, match.groups())) if match else None


def select(releases, candidate, override, resolve, ancestor):
    eligible = []
    seen = set()
    for release in releases:
        tag = release.get("tagName")
        if tag in seen:
            raise ValueError("Duplicate published release tag in release inventory")
        seen.add(tag)
        parsed = version(tag) if isinstance(tag, str) else None
        if release.get("isDraft") or release.get("isPrerelease") or parsed is None:
            continue
        if parsed < MINIMUM:
            continue
        commit = resolve(tag)
        if commit != candidate and ancestor(commit, candidate):
            eligible.append((parsed, tag, commit))
    if override:
        if version(override) is None or version(override) < MINIMUM:
            raise ValueError("baseline_tag must be a stable published v2.2.2+ tag")
        matches = [item for item in eligible if item[1] == override]
        if not matches:
            raise ValueError("baseline_tag is unpublished, equal to candidate, or not an ancestor")
        return matches[0][1:]
    if not eligible:
        raise ValueError("No compatible published stable ancestor release exists")
    return max(eligible)[1:]


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--baseline-tag", default="")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    candidate = git("rev-parse", f"{args.candidate}^{{commit}}")
    releases = json.loads(subprocess.check_output(
        ["gh", "release", "list", "--json", "tagName,isDraft,isPrerelease", "--limit", "1000"],
        text=True))
    tag, commit = select(releases, candidate, args.baseline_tag,
                         lambda name: git("rev-parse", f"refs/tags/{name}^{{commit}}"),
                         lambda old, new: subprocess.run(
                             ["git", "merge-base", "--is-ancestor", old, new], check=False).returncode == 0)
    with open(args.output, "a", encoding="utf-8") as stream:
        stream.write(f"tag={tag}\ncommit={commit}\ncandidate_commit={candidate}\n")
    print(f"Selected benchmark baseline {tag} at {commit}; candidate {candidate}")


if __name__ == "__main__":
    main()
