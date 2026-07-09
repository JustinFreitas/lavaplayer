import os
import re
import urllib.request
import json
import tomllib
import sys
import subprocess
from datetime import date

# Paths
ROOT_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOML_PATH = os.path.join(ROOT_DIR, "gradle", "libs.versions.toml")
REPORT_PATH = os.path.join(ROOT_DIR, "build", "dependencyUpdates", "report.json")

# Pins and configurations
NATIVE_REPOS = {
    # format: toml_key -> (github_owner_repo, use_releases_api, pin_version)
    "opus": ("xiph/opus", False, None),
    "ogg": ("xiph/ogg", True, None),
    "vorbis": ("xiph/vorbis", True, None),
    "samplerate": ("libsndfile/libsamplerate", True, None),
    "fdk-aac": ("mstorsjo/fdk-aac", False, None), # uses tags
}

def get_latest_github_version(owner_repo, use_releases_api=True):
    url = f"https://api.github.com/repos/{owner_repo}/releases/latest" if use_releases_api else f"https://api.github.com/repos/{owner_repo}/tags"
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            data = json.loads(response.read().decode('utf-8'))
            if use_releases_api:
                tag_name = data.get("tag_name", "")
                if tag_name.startswith("v"):
                    tag_name = tag_name[1:]
                return tag_name
            else:
                if data and isinstance(data, list):
                    for tag in data:
                        name = tag.get("name", "")
                        clean_name = name[1:] if name.startswith("v") else name
                        if re.match(r'^\d+\.\d+\.\d+$', clean_name):
                            return clean_name
    except Exception as e:
        print(f"Error fetching github version for {owner_repo}: {e}", file=sys.stderr)
    return None

def get_latest_mpg123_version():
    url = "https://www.mpg123.de/download/"
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            html = response.read().decode('utf-8')
            versions = re.findall(r'mpg123-(\d+\.\d+\.\d+)\.tar\.bz2', html)
            if versions:
                return max(versions, key=lambda s: [int(x) for x in s.split('.')])
    except Exception as e:
        print(f"Error fetching mpg123 version: {e}", file=sys.stderr)
    return None

def find_ref_for_library(group, name, toml_data):
    for lib_key, lib_val in toml_data.get("libraries", {}).items():
        if isinstance(lib_val, dict):
            lib_group = lib_val.get("group")
            lib_name = lib_val.get("name")
            if not lib_group or not lib_name:
                module = lib_val.get("module")
                if module and ":" in module:
                    lib_group, lib_name = module.split(":", 1)
            
            if lib_group == group and lib_name == name:
                version = lib_val.get("version")
                if isinstance(version, dict) and "ref" in version:
                    return version["ref"]
    return None

def find_ref_for_plugin(plugin_id, toml_data):
    for plugin_key, plugin_val in toml_data.get("plugins", {}).items():
        if isinstance(plugin_val, dict):
            if plugin_val.get("id") == plugin_id:
                version = plugin_val.get("version")
                if isinstance(version, dict) and "ref" in version:
                    return version["ref"]
    return None

def get_next_version():
    try:
        tags_output = subprocess.check_output(["git", "tag"], text=True).strip().split("\n")
    except Exception as e:
        print(f"Error running git tag: {e}", file=sys.stderr)
        tags_output = []

    pattern = re.compile(r'^v?(\d+\.\d+\.\d+)_(\d+)$')
    highest_suffix = -1
    base_version = "2.2.6"
    
    for tag in tags_output:
        match = pattern.match(tag)
        if match:
            b_ver, suffix_str = match.groups()
            suffix = int(suffix_str)
            if suffix > highest_suffix:
                highest_suffix = suffix
                base_version = b_ver
                
    if highest_suffix != -1:
        return f"v{base_version}_{highest_suffix + 1}"
    else:
        return "v2.2.6_24"

def update_changelog(next_version, changes):
    changelog_path = os.path.join(ROOT_DIR, "CHANGELOG.md")
    if not os.path.exists(changelog_path):
        print(f"Changelog not found at {changelog_path}", file=sys.stderr)
        return
    
    with open(changelog_path, "r", encoding="utf-8") as f:
        content = f.read()

    today = date.today().isoformat()
    new_section = f"## [{next_version.lstrip('v')}] - {today}\n"
    for change in changes:
        new_section += f"* {change}\n"
    new_section += "\n"

    # Insert after '# Change Log\n\n' or '# Change Log\n'
    pattern = re.compile(r'(# Change Log\n+)')
    match = pattern.search(content)
    if match:
        insert_pos = match.end()
        updated_content = content[:insert_pos] + new_section + content[insert_pos:]
        with open(changelog_path, "w", encoding="utf-8") as f:
            f.write(updated_content)
        print(f"Updated CHANGELOG.md with version {next_version}")
    else:
        print("Could not find '# Change Log' header in CHANGELOG.md", file=sys.stderr)

def main():
    if "--update-changelog" in sys.argv:
        changes_path = os.path.join(ROOT_DIR, "build", "dependency_changes.json")
        if os.path.exists(changes_path):
            with open(changes_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            update_changelog(data["next_version"], data["changes"])
            sys.exit(0)
        else:
            print("Error: build/dependency_changes.json not found", file=sys.stderr)
            sys.exit(1)

    if not os.path.exists(TOML_PATH):
        print(f"Error: libs.versions.toml not found at {TOML_PATH}", file=sys.stderr)
        sys.exit(1)

    with open(TOML_PATH, "rb") as f:
        toml_data = tomllib.load(f)

    current_versions = toml_data.get("versions", {})
    
    updates = {}
    changelog_entries = []
    natives_updated = False
    gradle_updated = False

    # 1. Check native versions
    print("Checking native versions...")
    for key, (repo, use_release, pin) in NATIVE_REPOS.items():
        current_val = current_versions.get(key)
        if not current_val:
            continue
        if pin:
            print(f"Native {key} is pinned to {pin} (current: {current_val})")
            continue
        
        latest = get_latest_github_version(repo, use_release)
        if latest and latest != current_val:
            print(f"Native update found: {key} {current_val} -> {latest}")
            updates[key] = latest
            changelog_entries.append(f"Updated native {key} to `{latest}` (was `{current_val}`)")
            natives_updated = True

    # Check mpg123
    current_mpg123 = current_versions.get("mpg123")
    if current_mpg123:
        latest_mpg123 = get_latest_mpg123_version()
        if latest_mpg123 and latest_mpg123 != current_mpg123:
            print(f"Native update found: mpg123 {current_mpg123} -> {latest_mpg123}")
            updates["mpg123"] = latest_mpg123
            changelog_entries.append(f"Updated native mpg123 to `{latest_mpg123}` (was `{current_mpg123}`)")
            natives_updated = True

    # 2. Check Gradle plugin and library updates from report.json
    if os.path.exists(REPORT_PATH):
        print("Reading Gradle dependency report...")
        with open(REPORT_PATH, "r", encoding="utf-8") as f:
            report = json.load(f)

        # Libraries
        for dep in report.get("outdated", {}).get("dependencies", []):
            group = dep.get("group")
            name = dep.get("name")
            current_val = dep.get("version")
            available = dep.get("available", {})
            latest = available.get("release") or available.get("milestone")
            
            if latest and latest != current_val:
                ref = find_ref_for_library(group, name, toml_data)
                if ref:
                    updates[ref] = latest
                    changelog_entries.append(f"Updated library `{group}:{name}` to `{latest}` (was `{current_val}`)")
                    gradle_updated = True

        # Plugins
        for dep in report.get("outdated", {}).get("dependencies", []):
            group = dep.get("group")
            name = dep.get("name")
            current_val = dep.get("version")
            available = dep.get("available", {})
            latest = available.get("release") or available.get("milestone")
            
            if name.endswith(".gradle.plugin"):
                plugin_id = name[:-14]
                ref = find_ref_for_plugin(plugin_id, toml_data)
                if ref:
                    updates[ref] = latest
                    changelog_entries.append(f"Updated plugin `{plugin_id}` to `{latest}` (was `{current_val}`)")
                    gradle_updated = True

        # Gradle Wrapper update checking
        gradle_info = report.get("gradle", {})
        if gradle_info.get("current", {}).get("isUpdateAvailable"):
            latest_gradle = gradle_info["current"]["version"]
            running_gradle = gradle_info["running"]["version"]
            print(f"Gradle wrapper update available: {running_gradle} -> {latest_gradle}")
            updates["gradle-wrapper"] = latest_gradle
            changelog_entries.append(f"Updated Gradle Wrapper to `{latest_gradle}` (was `{running_gradle}`)")
            gradle_updated = True
    else:
        print("Warning: report.json not found. Run ./gradlew dependencyUpdates first.", file=sys.stderr)

    # 3. Apply updates to libs.versions.toml
    toml_updated = False
    if updates:
        toml_updates = {k: v for k, v in updates.items() if k != "gradle-wrapper"}
        if toml_updates:
            with open(TOML_PATH, "r", encoding="utf-8") as f:
                lines = f.readlines()

            version_pattern = re.compile(r'^(\s*([a-zA-Z0-9_\-]+)\s*=\s*")([^"]+)(".*)$')
            updated_lines = []
            for line in lines:
                match = version_pattern.match(line)
                if match:
                    full_prefix, key, current_val, suffix = match.groups()
                    if key in toml_updates:
                        new_val = toml_updates[key]
                        line = f'{full_prefix}{new_val}{suffix}\n'
                        toml_updated = True
                updated_lines.append(line)

            if toml_updated:
                with open(TOML_PATH, "w", encoding="utf-8") as f:
                    f.writelines(updated_lines)
                print("Successfully updated libs.versions.toml.")

        # Update Gradle wrapper if needed
        if "gradle-wrapper" in updates:
            new_gradle = updates["gradle-wrapper"]
            print(f"Running wrapper update command to version {new_gradle}...")
            if os.name == "nt":
                cmd = "gradlew.bat wrapper --gradle-version " + new_gradle + " --no-daemon"
                shell = True
            else:
                cmd = ["./gradlew", "wrapper", "--gradle-version", new_gradle, "--no-daemon"]
                shell = False
            try:
                subprocess.check_call(cmd, shell=shell)
                print("Successfully updated Gradle Wrapper.")
            except Exception as e:
                print(f"Error updating Gradle Wrapper: {e}", file=sys.stderr)

    # 4. Generate outputs
    updated_flag = "true" if (toml_updated or "gradle-wrapper" in updates) else "false"
    next_ver = get_next_version()

    print(f"\nSummary of updates (Updated: {updated_flag}):")
    for entry in changelog_entries:
        print(f"- {entry}")

    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a") as f:
            f.write(f"updated={updated_flag}\n")
            f.write(f"natives_updated={'true' if natives_updated else 'false'}\n")
            f.write(f"gradle_updated={'true' if gradle_updated else 'false'}\n")
            f.write(f"next_version={next_ver}\n")
            
        github_summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if github_summary:
            with open(github_summary, "w") as f:
                f.write("### Dependency Scan Results\n")
                if changelog_entries:
                    f.write(f"**Status: Found updates! Proposed next release version: {next_ver}**\n\n")
                    for entry in changelog_entries:
                        f.write(f"- {entry}\n")
                else:
                    f.write("**Status: All dependencies are up to date!**\n")

    # Save details to build for release phase ingestion
    build_dir = os.path.join(ROOT_DIR, "build")
    os.makedirs(build_dir, exist_ok=True)
    with open(os.path.join(build_dir, "dependency_changes.json"), "w") as f:
        json.dump({
            "updated": updated_flag == "true",
            "natives_updated": natives_updated,
            "gradle_updated": gradle_updated,
            "next_version": next_ver,
            "changes": changelog_entries
        }, f, indent=2)

if __name__ == "__main__":
    main()
