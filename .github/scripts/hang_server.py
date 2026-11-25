"""Accepts connections and never answers: a dead IPTV feed that hangs instead of failing fast."""
import socket, threading, time
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("0.0.0.0", 8001)); s.listen(50)
held = []
while True:
    c, _ = s.accept(); held.append(c)
