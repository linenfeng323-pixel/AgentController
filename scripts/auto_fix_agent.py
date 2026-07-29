#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Android 自动修复代理。

工作流：
  1. 读取构建失败日志（本地文件或拉取 Actions 日志）
  2. 用规则匹配常见 Android 编译错误，自动生成补丁
  3. 提交并推送代码
  4. 触发新的 GitHub Actions 工作流
  5. 监控工作流直到完成，失败则回到步骤 1，最多 max_attempts 次
  6. 全程无需用户介入

依赖：requests（pip install requests）
未安装 LLM 依赖时退化为纯规则修复；配置 OPENAI_API_KEY 可调用大模型辅助。

用法：
  python3 auto_fix_agent.py --repo owner/name --token ghp_xxx --branch main \
      --run-id 123456 --log build_log.txt --max-attempts 8
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time
from typing import Optional

try:
    import requests
except ImportError:
    print("[ERROR] 缺少 requests，请先执行: pip install requests", file=sys.stderr)
    sys.exit(2)

GITHUB_API = "https://api.github.com"


def gh_headers(token: str) -> dict:
    # fine-grained PAT 用 Bearer；classic PAT 用 token，避免 401。
    if token.lower().startswith("github_pat_"):
        auth = f"Bearer {token}"
    else:
        auth = f"token {token}"
    return {
        "Authorization": auth,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }


def log(msg: str) -> None:
    print(f"[autofix] {msg}", flush=True)


# ===== 1. 获取失败日志 =====

def get_log_from_file(path: str) -> str:
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        return f.read()


def get_log_from_actions(token: str, repo: str, run_id: str) -> str:
    url = f"{GITHUB_API}/repos/{repo}/actions/runs/{run_id}/logs"
    r = requests.get(url, headers=gh_headers(token), allow_redirects=True, timeout=60)
    if r.status_code != 200:
        return f"(无法获取日志 {r.status_code}) {r.text[:500]}"
    # 日志是 zip，简单提取文本
    import io, zipfile
    try:
        z = zipfile.ZipFile(io.BytesIO(r.content))
        out = []
        for name in z.namelist():
            if name.endswith(".txt"):
                out.append(f"==== {name} ====")
                out.append(z.read(name).decode("utf-8", errors="ignore"))
        return "\n".join(out)
    except Exception as e:
        return f"(解压日志失败: {e})"


def extract_error_snippet(log_text: str, max_chars: int = 8000) -> str:
    """从大日志里抽取最关键错误片段。"""
    # 优先取 "FAILURE: Build failed" 之后内容
    idx = log_text.find("FAILURE: Build failed")
    if idx >= 0:
        return log_text[idx: idx + max_chars]
    # 兜底取 "error:" 行附近
    lines = log_text.splitlines()
    for i, line in enumerate(lines):
        if re.search(r"\berror\b", line, re.IGNORECASE):
            start = max(0, i - 10)
            return "\n".join(lines[start: i + 40])
    return log_text[-max_chars:]


# ===== 2. 规则化修复 =====

RULES = [
    # 未声明 Activity / Service
    {
        "name": "AndroidManifest 未声明组件",
        "pattern": r"Unable to resolve target activity.*?([A-Za-z0-9_.]+)",
        "fix": lambda m, files: ensure_component_in_manifest(m.group(1), files),
    },
    # 找不到符号（常见未 import）
    {
        "name": "未解析引用",
        "pattern": r"error: cannot find symbol[^\n]*\n\s*symbol:\s*class\s+(\w+)",
        "fix": lambda m, files: suggest_import(m.group(1), files),
    },
    # gridlayout 之类缺依赖
    {
        "name": "缺少 androidx 依赖",
        "pattern": r"package\s+(androidx\.[\w.]+)\s+does not exist",
        "fix": lambda m, files: add_androidx_dep(m.group(1), files),
    },
    # R 资源缺失
    {
        "name": "R 资源缺失",
        "pattern": r"error: cannot find symbol[^\n]*\n\s*location: class[^\n]*\n\s*variable\s+(\w+)",
        "fix": lambda m, files: create_missing_resource(m.group(1), files),
    },
]


def ensure_component_in_manifest(component: str, files: dict) -> list:
    """在 AndroidManifest 里补上组件声明。"""
    manifest = files.get("app/src/main/AndroidManifest.xml")
    if not manifest:
        return []
    if component in manifest:
        return []
    # 简单补 Activity
    short = component.split(".")[-1]
    entry = f'        <activity android:name=".{short}" android:exported="false" />'
    new_manifest = manifest.replace("</application>", entry + "\n    </application>")
    if new_manifest != manifest:
        files["app/src/main/AndroidManifest.xml"] = new_manifest
        return ["app/src/main/AndroidManifest.xml"]
    return []


ANDROIDX_DEPS = {
    "androidx.gridlayout": "androidx.gridlayout:gridlayout:1.0.0",
    "androidx.recyclerview": "androidx.recyclerview:recyclerview:1.3.2",
    "androidx.constraintlayout": "androidx.constraintlayout:constraintlayout:2.1.4",
    "androidx.appcompat": "androidx.appcompat:appcompat:1.6.1",
    "com.google.android.material": "com.google.android.material:material:1.11.0",
}


def add_androidx_dep(pkg: str, files: dict) -> list:
    gradle = files.get("app/build.gradle")
    if not gradle:
        return []
    dep = ANDROIDX_DEPS.get(pkg)
    if not dep or dep in gradle:
        return []
    new = gradle.replace(
        "dependencies {",
        f"dependencies {{\n    implementation '{dep}'",
    )
    files["app/build.gradle"] = new
    return ["app/build.gradle"]


COMMON_IMPORTS = {
    "GridLayoutManager": "androidx.gridlayout.widget.GridLayoutManager",
    "RecyclerView": "androidx.recyclerview.widget.RecyclerView",
    "LinearLayoutManager": "androidx.recyclerview.widget.LinearLayoutManager",
}


def suggest_import(symbol: str, files: dict) -> list:
    imp = COMMON_IMPORTS.get(symbol)
    if not imp:
        return []
    changed = []
    for path, content in list(files.items()):
        if not path.endswith(".kt"):
            continue
        if symbol not in content:
            continue
        if imp in content:
            continue
        # 插入到 package 之后
        new = re.sub(
            r"(^package [\w.]+\n)",
            r"\1\nimport " + imp + "\n",
            content,
            count=1,
        )
        if new != content:
            files[path] = new
            changed.append(path)
    return list(set(changed))


def create_missing_resource(name: str, files: dict) -> list:
    """对常见缺失资源做最小兜底（drawable / string）。"""
    if name.startswith("ic_"):
        path = f"app/src/main/res/drawable/{name}.xml"
        if path not in files:
            files[path] = (
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"\n'
                '    android:tint="#FFFFFF">\n'
                '    <path android:fillColor="#FFFFFFFF" android:pathData="M3,3h18v18h-18z" />\n'
                '</vector>\n'
            )
            return [path]
    return []


def apply_rules(log_text: str, files: dict) -> list:
    changed = []
    for rule in RULES:
        for m in re.finditer(rule["pattern"], log_text):
            changed += rule["fix"](m, files)
    return list(dict.fromkeys(changed))  # 去重保序


# ===== 3. LLM 辅助（可选） =====

def llm_fix(log_text: str, files: dict, changed: list) -> list:
    """如果配置了 OPENAI_API_KEY，调用大模型给修复建议（仅打印，不自动改写，避免误伤）。"""
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        return changed
    log("[LLM] 检测到 OPENAI_API_KEY，尝试让大模型分析（仅建议）")
    snippet = extract_error_snippet(log_text, 4000)
    prompt = (
        "你是 Android 编译错误修复专家。下面是失败的 Gradle 构建日志片段，"
        "请用一句话给出最可能的修复方向，不要输出代码补丁：\n\n" + snippet
    )
    try:
        r = requests.post(
            "https://api.openai.com/v1/chat/completions",
            headers={"Authorization": f"Bearer {key}"},
            json={"model": "gpt-4o-mini", "messages": [{"role": "user", "content": prompt}], "max_tokens": 200},
            timeout=60,
        )
        if r.status_code == 200:
            suggestion = r.json()["choices"][0]["message"]["content"]
            log("[LLM] 建议: " + suggestion)
    except Exception as e:
        log(f"[LLM] 调用失败: {e}")
    return changed


# ===== 4. 提交并推送 =====

def commit_and_push(token: str, repo: str, branch: str, changed_files: list, attempt: int) -> bool:
    if not changed_files:
        log("本轮无文件变更可提交")
        return False
    msg = f"fix(auto): 自动修复编译 #{attempt} - {', '.join(changed_files[:5])}"
    # 直接用 git 命令（Actions runner 已 checkout）
    try:
        subprocess.run(["git", "config", "user.name", "auto-fix-agent"], check=True)
        subprocess.run(["git", "config", "user.email", "auto-fix@users.noreply.github.com"], check=True)
        for f in changed_files:
            subprocess.run(["git", "add", f], check=True)
        subprocess.run(["git", "commit", "-m", msg], check=True)
        # 推送
        remote = f"https://x-access-token:{token}@github.com/{repo}.git"
        subprocess.run(["git", "push", remote, branch], check=True)
        log(f"已推送提交: {msg}")
        return True
    except subprocess.CalledProcessError as e:
        log(f"提交/推送失败: {e}")
        return False


# ===== 5. 触发并监控工作流 =====

def trigger_workflow(token: str, repo: str) -> Optional[int]:
    """触发 android-build 工作流，返回新 run id。"""
    url = f"{GITHUB_API}/repos/{repo}/actions/workflows/android-build.yml/dispatches"
    r = requests.post(
        url,
        headers=gh_headers(token),
        json={"ref": os.environ.get("AUTOFIX_REF", "main")},
        timeout=30,
    )
    if r.status_code not in (200, 204):
        log(f"触发失败: {r.status_code} {r.text}")
        return None
    # 等待 run 出现
    for _ in range(20):
        time.sleep(5)
        runs = requests.get(
            f"{GITHUB_API}/repos/{repo}/actions/runs?per_page=5",
            headers=gh_headers(token),
            timeout=30,
        ).json()
        for run in runs.get("workflow_runs", []):
            if run["name"] == "Android Build" and run["status"] in ("queued", "in_progress"):
                return run["id"]
    return None


def monitor_run(token: str, repo: str, run_id: int, timeout: int = 1800) -> str:
    """监控工作流，返回 conclusion。"""
    url = f"{GITHUB_API}/repos/{repo}/actions/runs/{run_id}"
    start = time.time()
    while time.time() - start < timeout:
        r = requests.get(url, headers=gh_headers(token), timeout=30).json()
        status = r.get("status")
        if status == "completed":
            return r.get("conclusion", "failure")
        log(f"工作流 {run_id} 状态: {status}，已等待 {int(time.time()-start)}s")
        time.sleep(15)
    return "timeout"


# ===== 主循环 =====

def get_repo_files(token: str, repo: str, branch: str) -> dict:
    """获取仓库关键文件内容（用于在内存里改写）。"""
    files = {}
    targets = [
        "app/build.gradle",
        "app/src/main/AndroidManifest.xml",
    ]
    # 列出 kotlin 文件
    try:
        r = requests.get(
            f"{GITHUB_API}/repos/{repo}/git/trees/{branch}?recursive=1",
            headers=gh_headers(token),
            timeout=30,
        ).json()
        for item in r.get("tree", []):
            p = item.get("path", "")
            if p.endswith(".kt") or p in targets or p.startswith("app/src/main/res/"):
                targets.append(p)
    except Exception:
        pass
    for path in targets:
        try:
            r = requests.get(
                f"{GITHUB_API}/repos/{repo}/contents/{path}?ref={branch}",
                headers=gh_headers(token),
                timeout=30,
            )
            if r.status_code == 200:
                data = r.json()
                if data.get("encoding") == "base64":
                    files[path] = base64.b64decode(data["content"]).decode("utf-8", errors="ignore")
        except Exception:
            pass
    return files


def write_back_files(token: str, repo: str, branch: str, files: dict) -> list:
    """把改后的文件通过 GitHub API 写回（用于非 Actions 环境）。"""
    changed = []
    # 先取 branch 最新 sha
    try:
        ref = requests.get(f"{GITHUB_API}/repos/{repo}/git/refs/heads/{branch}", headers=gh_headers(token), timeout=30).json()
        branch_sha = ref["object"]["sha"]
        tree = requests.get(f"{GITHUB_API}/repos/{repo}/git/trees/{branch_sha}?recursive=1", headers=gh_headers(token), timeout=30).json()
    except Exception as e:
        log(f"无法读取分支树: {e}")
        return []
    # 写每个文件
    for path, content in files.items():
        # 仅写实际改过的（简单比较内容长度变化或 hash）
        blob = requests.post(
            f"{GITHUB_API}/repos/{repo}/git/blobs",
            headers=gh_headers(token),
            json={"content": content, "encoding": "utf-8"},
            timeout=30,
        ).json()
        changed.append({"path": path, "sha": blob["sha"], "mode": "100644", "type": "blob"})
    if not changed:
        return []
    # 创建 tree
    new_tree = requests.post(
        f"{GITHUB_API}/repos/{repo}/git/trees",
        headers=gh_headers(token),
        json={"base_tree": tree["sha"], "tree": changed},
        timeout=30,
    ).json()
    # commit
    commit = requests.post(
        f"{GITHUB_API}/repos/{repo}/git/commits",
        headers=gh_headers(token),
        json={"message": f"fix(auto): 修复编译", "tree": new_tree["sha"], "parents": [branch_sha]},
        timeout=30,
    ).json()
    # 更新 ref
    requests.patch(
        f"{GITHUB_API}/repos/{repo}/git/refs/heads/{branch}",
        headers=gh_headers(token),
        json={"sha": commit["sha"]},
        timeout=30,
    )
    return [c["path"] for c in changed]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--branch", default="main")
    ap.add_argument("--run-id")
    ap.add_argument("--log", help="本地构建日志路径")
    ap.add_argument("--max-attempts", type=int, default=8)
    args = ap.parse_args()

    os.environ.setdefault("AUTOFIX_REF", args.branch)

    log(f"开始自动修复 | repo={args.repo} branch={args.branch} max={args.max_attempts}")

    current_log = ""
    if args.log and os.path.exists(args.log):
        current_log = get_log_from_file(args.log)
    elif args.run_id:
        current_log = get_log_from_actions(args.token, args.repo, args.run_id)
    if not current_log:
        log("无日志可分析，退出")
        sys.exit(0)

    for attempt in range(1, args.max_attempts + 1):
        log(f"===== 修复尝试 {attempt}/{args.max_attempts} =====")
        snippet = extract_error_snippet(current_log)
        log("错误片段:\n" + snippet[:1500])

        files = get_repo_files(args.token, args.repo, args.branch)
        changed = apply_rules(snippet, files)
        changed = llm_fix(snippet, files, changed)

        if not changed:
            log("规则无法修复，停止（建议人工介入）")
            sys.exit(1)

        # 写回（在 Actions runner 上直接 git；在本地则用 API）
        if os.path.exists(".git"):
            ok = commit_and_push(args.token, args.repo, args.branch, changed, attempt)
        else:
            paths = write_back_files(args.token, args.repo, args.branch, files)
            ok = bool(paths)

        if not ok:
            log("本轮无变更提交，停止")
            sys.exit(1)

        # 触发新工作流
        new_run = trigger_workflow(args.token, args.repo)
        if not new_run:
            log("触发新工作流失败，停止")
            sys.exit(1)
        conclusion = monitor_run(args.token, args.repo, new_run)
        log(f"工作流 {new_run} 完成: {conclusion}")
        if conclusion == "success":
            log("编译成功，自动修复完成")
            sys.exit(0)
        # 失败则拉新日志继续
        current_log = get_log_from_actions(args.token, args.repo, str(new_run))

    log(f"已达最大尝试次数 {args.max_attempts}，仍有错误，需人工介入")
    sys.exit(1)


if __name__ == "__main__":
    main()
