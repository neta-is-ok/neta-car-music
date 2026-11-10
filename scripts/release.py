#!/usr/bin/env python3
"""本地构建正式签名包；更新说明确认后，上传并公开 GitHub Release。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "neta-is-ok/neta-car-music"


def command(*args, timeout=None):
    return subprocess.run(
        args, cwd=ROOT, check=True, text=True, capture_output=True, timeout=timeout,
    ).stdout.strip()


def retry(label, operation):
    attempts = int(os.environ.get("RELEASE_RETRIES", "5"))
    delay = int(os.environ.get("RELEASE_RETRY_DELAY", "5"))
    if attempts < 1 or delay < 0:
        raise ValueError("RELEASE_RETRIES 必须大于 0，RELEASE_RETRY_DELAY 不能小于 0")
    for attempt in range(1, attempts + 1):
        try:
            return operation()
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            detail = error.stderr if isinstance(error, subprocess.CalledProcessError) else "请求超时"
            print(f"{label}失败（{attempt}/{attempts}）：{detail or error}", file=sys.stderr)
            if attempt == attempts:
                raise RuntimeError("网络操作未完成；本地产物和已有草稿保留，可重跑 publish") from error
            wait = delay * attempt
            print(f"{wait} 秒后重试……", file=sys.stderr)
            time.sleep(wait)


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def version():
    values = dict(
        line.split("=", 1) for line in (ROOT / "version.properties").read_text().splitlines()
        if line and not line.startswith("#")
    )
    name, code = values["versionName"], int(values["versionCode"])
    return name, code, f"v{name}-{code}"


def update_notes(text):
    # 应用内更新窗口按纯文本逐条显示；GitHub Release 保留完整 Markdown。
    return [
        (line[2:] if line.startswith("- ") else line).replace("**", "")
        for line in (line.strip() for line in text.splitlines())
        if line and not line.startswith("#")
    ]


def prepare(notes_path):
    name, code, tag = version()
    notes_path = notes_path.resolve()
    if ROOT not in notes_path.parents:
        raise ValueError("更新说明必须存放在当前项目内")
    notes = notes_path.read_text(encoding="utf-8").strip() + "\n"
    if not notes.strip():
        raise ValueError("更新说明不能为空")
    if command("git", "branch", "--show-current") != "main":
        raise ValueError("请在 main 分支准备发布")
    if command("git", "status", "--porcelain"):
        raise ValueError("请先提交本次源码和版本，确保发布标签对应已构建源码；不要清理他人改动")
    commit = command("git", "rev-parse", "HEAD")
    directory = ROOT / "dist" / tag
    plan_path = directory / "release.json"
    if plan_path.exists():
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        if plan["commit"] == commit and all(
            (directory / filename).is_file()
            for filename in (plan["apk"], "update.json", "update.apk.sha256")
        ):
            manifest_path = directory / "update.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["notes"] = update_notes(notes)
            write_json(manifest_path, manifest)
            (directory / "release-notes.md").write_text(notes, encoding="utf-8")
            print(f"已复用 {tag} 的签名产物并更新说明；确认后执行 publish。")
            return
    for filename in ("keystore.jks", "signing.properties"):
        if not (ROOT / filename).is_file():
            raise ValueError(f"正式发布需要仓库根目录的 {filename}")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("请先配置 ANDROID_HOME 或 ANDROID_SDK_ROOT")
    versions = (ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    build_tools = re.search(r'^buildTools = "([^"]+)"', versions, re.MULTILINE).group(1)
    min_sdk = int(re.search(r'^minSdk = "(\d+)"', versions, re.MULTILINE).group(1))
    print(f"本地编译并签名 {tag}……", flush=True)
    subprocess.run(
        ["./gradlew", ":app:assembleRelease", "--max-workers=1", f"-PreleaseRepository={REPOSITORY}"],
        cwd=ROOT, check=True, env={**os.environ, "KEYSTORE": str(ROOT / "keystore.jks")},
    )
    output = ROOT / "app/build/outputs/apk/release"
    metadata = json.loads((output / "output-metadata.json").read_text(encoding="utf-8"))
    element = metadata["elements"][0]
    apk_name = f"neta-car-music-{name}-{code}.apk"
    if (metadata["applicationId"], element["versionName"], element["versionCode"], element["outputFile"]) != (
        "io.neta.music", name, code, apk_name,
    ):
        raise ValueError("APK 包名或版本与本次发布不一致")
    signature = command(str(Path(sdk) / "build-tools" / build_tools / "apksigner"),
                        "verify", "--print-certs", str(output / apk_name))
    if "CN=Android Debug" in signature:
        raise ValueError("不能发布 debug 签名 APK")
    for line in signature.splitlines():
        if "certificate DN:" in line or "certificate SHA-256 digest:" in line:
            print(line)
    directory.mkdir(parents=True, exist_ok=True)
    apk = directory / apk_name
    shutil.copyfile(output / apk_name, apk)
    sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    published = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    write_json(directory / "update.json", {
        "schema": "neta.music.update", "channel": "stable", "versionCode": code,
        "versionName": name, "tag": tag, "publishedAt": published, "minSdk": min_sdk,
        "apk": {
            "url": f"https://github.com/{REPOSITORY}/releases/download/{tag}/{apk_name}",
            "size": apk.stat().st_size, "sha256": sha,
        },
        "notes": update_notes(notes),
    })
    (directory / "update.apk.sha256").write_text(f"{sha}  {apk_name}\n", encoding="utf-8")
    (directory / "release-notes.md").write_text(notes, encoding="utf-8")
    write_json(plan_path, {"tag": tag, "versionName": name, "versionCode": code, "commit": commit, "apk": apk_name})
    print(f"本地准备完成：{directory.relative_to(ROOT)}。请先展示并确认更新说明，再执行 publish。")


def find_release(tag):
    result = subprocess.run(
        ["gh", "api", f"repos/{REPOSITORY}/releases/tags/{tag}"],
        cwd=ROOT, text=True, capture_output=True, timeout=300,
    )
    if result.returncode and "HTTP 404" in result.stderr:
        return None
    result.check_returncode()
    return json.loads(result.stdout)


def publish():
    _, _, tag = version()
    directory = ROOT / "dist" / tag
    plan = json.loads((directory / "release.json").read_text(encoding="utf-8"))
    notes_path = directory / "release-notes.md"
    print(f"\n吒友爱听 {plan['versionName']} ({plan['versionCode']}) · {tag}\n")
    print(notes_path.read_text(encoding="utf-8"))
    if input(f"确认发布以上更新内容，请输入 {tag}：").strip() != tag:
        print("未确认，不推送、不创建 Release。")
        return
    for name in (plan["apk"], "update.json", "update.apk.sha256"):
        if not (directory / name).is_file():
            raise ValueError(f"缺少发布附件 {name}，请先执行 prepare")
    ref = f"refs/tags/{tag}"
    exists = subprocess.run(["git", "show-ref", "--verify", "--quiet", ref], cwd=ROOT).returncode == 0
    if exists:
        if command("git", "rev-parse", f"{ref}^{{commit}}") != plan["commit"]:
            raise ValueError(f"已有标签 {tag} 指向其他提交，不覆盖")
    else:
        command("git", "tag", "-a", tag, plan["commit"], "-m", f"吒友爱听 {plan['versionName']} ({plan['versionCode']})")
    retry("推送发布提交", lambda: command("git", "push", "origin", f"{plan['commit']}:refs/heads/main", timeout=300))
    retry("推送版本标签", lambda: command("git", "push", "origin", ref, timeout=300))
    release_url = f"https://github.com/{REPOSITORY}/releases/tag/{tag}"

    def draft():
        # 创建请求若已在服务器成功、但本地断线，下次尝试直接接续已有草稿。
        existing = find_release(tag)
        if existing is not None:
            return existing
        command("gh", "release", "create", tag, "--repo", REPOSITORY,
                "--verify-tag", "--draft", "--title", f"吒友爱听 {plan['versionName']} ({plan['versionCode']})",
                "--notes-file", str(notes_path), timeout=300)
        return {"draft": True}

    release = retry("创建或接续发布草稿", draft)
    if not release["draft"]:
        print(f"该版本已经发布：{release_url}")
        return
    for name in (plan["apk"], "update.json", "update.apk.sha256"):
        print(f"上传 {name}……", flush=True)
        retry(f"上传 {name}", lambda name=name: command(
            "gh", "release", "upload", tag, str(directory / name), "--repo", REPOSITORY, "--clobber", timeout=300,
        ))
    retry("公开 Release", lambda: command(
        "gh", "release", "edit", tag, "--repo", REPOSITORY, "--draft=false", "--latest",
        "--notes-file", str(notes_path), timeout=300,
    ))
    print(f"发布完成：{release_url}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    build = commands.add_parser("prepare", help="本地编译、签名并生成更新附件，不执行远端操作")
    build.add_argument("--notes", required=True, type=Path, help="项目内的完整 Markdown 更新说明")
    commands.add_parser("publish", help="确认更新说明，重试推送和上传，全部完成后公开 Release")
    args = parser.parse_args()
    try:
        if args.action == "prepare":
            prepare(args.notes)
        else:
            publish()
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError, EOFError) as error:
        print(f"发布未完成：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
