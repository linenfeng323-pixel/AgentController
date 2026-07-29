#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GitHub Actions 工作流监控脚本。

用途：在自动修复代理循环中持续监控某个工作流，直到完成或超时；
也支持独立使用：监控指定 repo 最新的一次 Android Build 工作流，
成功/失败/进行中实时打印，并获取最终编译产物（APK）下载链接。

依赖：requests
用法：
  python3 workflow_monitor.py --repo owner/name --token ghp_xxx --watch
  python3 workflow_monitor.py --repo owner/name --token ghp_xxx --run-id 12345
"""
from __future__ import annotations

import argparse
import sys
import time
import requests

GITHUB_API = "https://api.github.com"


def headers(token: str) -> dict:
    return {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }


def get_run(token: str, repo: str, run_id: int) -> dict:
    r = requests.get(f"{GITHUB_API}/repos/{repo}/actions/runs/{run_id}",
                     headers=headers(token), timeout=30)
    r.raise_for_status()
    return r.json()


def latest_run(token: str, repo: str, name: str = "Android Build") -> dict | None:
    r = requests.get(f"{GITHUB_API}/repos/{repo}/actions/runs?per_page=20",
                     headers=headers(token), timeout=30)
    r.raise_for_status()
    for run in r.json().get("workflow_runs", []):
        if run["name"] == name:
            return run
    return None


def artifacts(token: str, repo: str, run_id: int) -> list:
    r = requests.get(f"{GITHUB_API}/repos/{repo}/actions/runs/{run_id}/artifacts",
                     headers=headers(token), timeout=30)
    r.raise_for_status()
    return r.json().get("artifacts", [])


def watch(token: str, repo: str, run_id: int, timeout: int = 1800) -> str:
    start = time.time()
    last_status = None
    while time.time() - start < timeout:
        run = get_run(token, repo, run_id)
        status = run.get("status")
        if status != last_status:
            print(f"[monitor] run {run_id} -> {status}", flush=True)
            last_status = status
        if status == "completed":
            conclusion = run.get("conclusion", "failure")
            print(f"[monitor] 完成: {conclusion}", flush=True)
            # 打印产物
            arts = artifacts(token, repo, run_id)
            if arts:
                print("[monitor] 编译产物:", flush=True)
                for a in arts:
                    print(f"  - {a['name']} (size={a['size_in_bytes']}) "
                          f"下载: https://github.com/{repo}/actions/runs/{run_id}/artifacts/{a['id']}", flush=True)
            else:
                print("[monitor] 无产物（可能编译失败）", flush=True)
            return conclusion
        time.sleep(15)
    print("[monitor] 超时", flush=True)
    return "timeout"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--run-id", type=int)
    ap.add_argument("--watch", action="store_true", help="监控最新一次 Android Build")
    ap.add_argument("--timeout", type=int, default=1800)
    args = ap.parse_args()

    run_id = args.run_id
    if not run_id and args.watch:
        run = latest_run(args.token, args.repo)
        if not run:
            print("[monitor] 未找到 Android Build 工作流", file=sys.stderr)
            sys.exit(1)
        run_id = run["id"]
        print(f"[monitor] 监控最新工作流 run_id={run_id}", flush=True)

    conclusion = watch(args.token, args.repo, run_id, args.timeout)
    sys.exit(0 if conclusion == "success" else 1)


if __name__ == "__main__":
    main()
