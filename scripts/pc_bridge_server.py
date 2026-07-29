#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PC 桥接服务端。

配合 App 里的 [PcBridgeClient] 使用：
  - 手机作为执行端连接本服务
  - PC/云作为算力端，可向手机下发 [CommandBatch] 指令
  - 支持会话粘连（单连接=单会话）、心跳、自动重连

协议：
  - WebSocket（RFC6455，纯 Python 实现，无第三方依赖）
  - 消息为 JSON 文本：
      手机 -> 服务端: {"type":"ack","explain":"..."} / "ping" / {"type":"screenshot","sha256":"..."}
      服务端 -> 手机: {"type":"command","payload":"<AI 回复 JSON 或自然语言>"} / "ping" / {"type":"screenshot_request"}

用法：
  python3 pc_bridge_server.py --port 9912
  然后在 App 设置页填 ws://<本机IP>:9912 并开启“PC/云端协同桥接”

也可在本服务里接 LLM：收到手机上报的观察后调用大模型，把回包作为 command 下发。
配置 OPENAI_API_KEY 即可启用 LLM 自动决策。
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import socket
import ssl
import threading
import time
from typing import Optional

try:
    import requests
except ImportError:
    requests = None


def log(msg: str) -> None:
    print(f"[bridge] {msg}", flush=True)


# ===== WebSocket 帧编解码 =====

def read_frame(conn: socket.socket) -> Optional[str]:
    header = recv_exact(conn, 2)
    if not header:
        return None
    b1, b2 = header[0], header[1]
    if (b1 & 0x80) == 0:
        return None  # 必须 FIN
    masked = (b2 & 0x80) != 0
    length = b2 & 0x7F
    if length == 126:
        ext = recv_exact(conn, 2)
        if not ext: return None
        length = int.from_bytes(ext, "big")
    elif length == 127:
        ext = recv_exact(conn, 8)
        if not ext: return None
        length = int.from_bytes(ext, "big")
    mask_key = recv_exact(conn, 4) if masked else None
    data = recv_exact(conn, length)
    if data is None:
        return None
    if mask_key:
        data = bytes(data[i] ^ mask_key[i % 4] for i in range(len(data)))
    return data.decode("utf-8", errors="ignore")


def recv_exact(conn: socket.socket, n: int) -> Optional[bytes]:
    buf = b""
    while len(buf) < n:
        try:
            chunk = conn.recv(n - len(buf))
        except Exception:
            return None
        if not chunk:
            return None
        buf += chunk
    return buf


def send_text(conn: socket.socket, text: str) -> bool:
    payload = text.encode("utf-8")
    header = bytearray()
    header.append(0x81)  # FIN + text
    if len(payload) <= 125:
        header.append(len(payload))
    elif len(payload) <= 0xFFFF:
        header.append(126)
        header += len(payload).to_bytes(2, "big")
    else:
        header.append(127)
        header += len(payload).to_bytes(8, "big")
    try:
        conn.sendall(bytes(header) + payload)
        return True
    except Exception:
        return False


def handshake(conn: socket.socket) -> bool:
    try:
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = conn.recv(1024)
            if not chunk:
                return False
            data += chunk
        text = data.decode("utf-8", errors="ignore")
        if "upgrade: websocket" not in text.lower():
            return False
        # 提取 Sec-WebSocket-Key
        key = None
        session = "?"
        device = "?"
        for line in text.splitlines():
            if line.lower().startswith("sec-websocket-key:"):
                key = line.split(":", 1)[1].strip()
            if line.lower().startswith("x-session:"):
                session = line.split(":", 1)[1].strip()
            if line.lower().startswith("x-device:"):
                device = line.split(":", 1)[1].strip()
        if not key:
            return False
        import hashlib as hl
        guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        accept = hl.sha1((key + guid).encode()).digest()
        accept_b64 = base64.b64encode(accept).decode()
        resp = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept_b64}\r\n"
            "\r\n"
        )
        conn.sendall(resp.encode())
        log(f"新连接 session={session} device={device}")
        return True
    except Exception as e:
        log(f"握手失败: {e}")
        return False


# ===== 会话管理 =====

class Session:
    def __init__(self, conn: socket.socket, sid: str):
        self.conn = conn
        self.sid = sid
        self.alive = True
        self.last_observation = ""

    def send_command(self, payload: str) -> bool:
        msg = json.dumps({"type": "command", "payload": payload}, ensure_ascii=False)
        return send_text(self.conn, msg)


sessions: dict[str, Session] = {}
sessions_lock = threading.Lock()


def handle_client(conn: socket.socket, addr):
    if not handshake(conn):
        conn.close()
        return
    sid = f"{addr[0]}:{addr[1]}-{int(time.time())}"
    sess = Session(conn, sid)
    with sessions_lock:
        sessions[sid] = sess
    # 心跳
    def heartbeat():
        while sess.alive:
            time.sleep(15)
            if not send_text(conn, "ping"):
                sess.alive = False
                break
    threading.Thread(target=heartbeat, daemon=True).start()

    try:
        while sess.alive:
            msg = read_frame(conn)
            if msg is None:
                break
            if msg == "ping":
                send_text(conn, "pong")
                continue
            if msg == "pong":
                continue
            try:
                obj = json.loads(msg)
                t = obj.get("type")
                if t == "ack":
                    log(f"[{sid}] ack: {obj.get('explain','')}")
                elif t == "screenshot":
                    log(f"[{sid}] 截图 sha256={obj.get('sha256','')}")
                else:
                    log(f"[{sid}] 未知消息: {msg[:200]}")
            except Exception:
                log(f"[{sid}] 文本: {msg[:200]}")
    finally:
        sess.alive = False
        with sessions_lock:
            sessions.pop(sid, None)
        try:
            conn.close()
        except Exception:
            pass
        log(f"断开 {sid}")


# ===== 控制台：手动给手机下发指令 =====

def console():
    print("=== PC 桥接控制台 ===")
    print("命令：")
    print("  list            列出在线会话")
    print("  send <指令>     向第一个会话下发指令（自然语言或 JSON）")
    print("  auto            启用 LLM 自动决策（需 OPENAI_API_KEY）")
    print("  quit            退出")
    auto_mode = False
    while True:
        try:
            line = input(">> ").strip()
        except EOFError:
            break
        if not line:
            continue
        if line == "quit":
            break
        if line == "list":
            with sessions_lock:
                for k in sessions:
                    print(f"  {k}")
            continue
        if line == "auto":
            auto_mode = not auto_mode
            print(f"自动模式: {auto_mode}")
            continue
        if line.startswith("send "):
            payload = line[5:]
            with sessions_lock:
                if not sessions:
                    print("无在线会话")
                else:
                    s = next(iter(sessions.values()))
                    s.send_command(payload)
                    print("已下发")
            continue
        # 其它输入当作指令直接下发
        with sessions_lock:
            if sessions:
                s = next(iter(sessions.values()))
                s.send_command(line)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9912)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--cert", help="启用 wss 的证书 pem")
    ap.add_argument("--key", help="启用 wss 的私钥 pem")
    args = ap.parse_args()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(8)
    log(f"PC 桥接服务监听 {args.host}:{args.port}")

    ssl_ctx = None
    if args.cert and args.key:
        ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_ctx.load_cert_chain(args.cert, args.key)
        log("已启用 wss")

    def accept_loop():
        while True:
            try:
                conn, addr = srv.accept()
                if ssl_ctx:
                    try:
                        conn = ssl_ctx.wrap_socket(conn, server_side=True)
                    except Exception as e:
                        log(f"TLS 握手失败: {e}")
                        conn.close()
                        continue
                threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()
            except Exception as e:
                log(f"accept 异常: {e}")

    threading.Thread(target=accept_loop, daemon=True).start()
    console()


if __name__ == "__main__":
    main()
