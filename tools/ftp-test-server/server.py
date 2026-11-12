"""
Test FTP server for CamLink USB's auto-upload, reachable over Tailscale only.

    python server.py            # listens on this PC's Tailscale IPv4, port 2121

Login is generated on first run and kept in credentials.txt next to this script.
Uploaded photos land in ./uploads/.
"""
import os
import secrets
import subprocess
import sys

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
UPLOADS = os.path.join(HERE, "uploads")
CREDS = os.path.join(HERE, "credentials.txt")
PORT = 2121
PASSIVE_PORTS = range(60000, 60020)


def tailscale_ip() -> str:
    try:
        out = subprocess.run(["tailscale", "ip", "-4"], capture_output=True, text=True, timeout=10).stdout.strip()
        if out:
            return out.splitlines()[0]
    except Exception:
        pass
    sys.exit("Could not get the Tailscale IPv4 address (is Tailscale running?)")


def credentials():
    if os.path.exists(CREDS):
        kv = dict(line.split("=", 1) for line in open(CREDS, encoding="utf-8").read().splitlines() if "=" in line)
        return kv["user"], kv["password"]
    user, pw = "camlink", secrets.token_urlsafe(12)
    with open(CREDS, "w", encoding="utf-8") as f:
        f.write(f"user={user}\npassword={pw}\n")
    return user, pw


class LoggingHandler(FTPHandler):
    def on_login(self, username):
        print(f"[login] {username} from {self.remote_ip}", flush=True)

    def on_file_received(self, file):
        print(f"[received] {os.path.basename(file)}  {os.path.getsize(file) / 1048576:.1f} MB", flush=True)

    def on_incomplete_file_received(self, file):
        print(f"[incomplete] {os.path.basename(file)} (removed)", flush=True)
        try:
            os.remove(file)
        except OSError:
            pass


def main():
    os.makedirs(UPLOADS, exist_ok=True)
    ip = tailscale_ip()
    user, pw = credentials()

    auth = DummyAuthorizer()
    # e=cd, l=list, r=read, a=append, d=delete, f=rename, m=mkdir, w=write
    auth.add_user(user, pw, UPLOADS, perm="elradfmw")

    LoggingHandler.authorizer = auth
    LoggingHandler.passive_ports = PASSIVE_PORTS
    LoggingHandler.banner = "CamLink USB test FTP server"

    server = FTPServer((ip, PORT), LoggingHandler)
    print(f"FTP test server on {ip}:{PORT}  (passive ports {PASSIVE_PORTS.start}-{PASSIVE_PORTS.stop - 1})", flush=True)
    print(f"Login: see {CREDS}", flush=True)
    print(f"Uploads: {UPLOADS}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
