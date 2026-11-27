"""A dead IPTV feed that hangs instead of failing fast: it answers with headers and a few KB of empty TS packets,
then sends nothing more (as the dead first feed of auto-report #62, which defeated a "not one byte" rule).
A connection that sends nothing at all is covered too: the app must leave both after about 2.5 s."""
import socket, threading

NULL_TS = (b"\x47\x1f\xff\x10" + b"\xff" * 184) * 22   # 22 null packets, about 4 KB
HEAD = b"HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: keep-alive\r\n\r\n"

held = []

def serve(c):
    try:
        c.settimeout(10)
        c.recv(4096)
        c.sendall(HEAD + NULL_TS)
    except OSError:
        pass
    held.append(c)   # keep it open and silent

s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("0.0.0.0", 8001)); s.listen(50)
while True:
    c, _ = s.accept()
    threading.Thread(target=serve, args=(c,), daemon=True).start()
