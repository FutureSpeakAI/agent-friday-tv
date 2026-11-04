"""Find Android TV and Google TV devices on the local network with multicast DNS.

Standard library only. Sends one PTR query for each service below, listens for a few
seconds, and groups the answers by IP address. Nothing leaves the local network.

  _adb-tls-pairing._tcp   Wireless debugging is showing a pairing code (the pairing port)
  _adb-tls-connect._tcp   Wireless debugging is on (the port adb connects to)
  _androidtvremote2._tcp  An Android TV / Google TV that accepts the Google TV remote protocol
  _googlecast._tcp        A Cast receiver (TXT fn = friendly name, md = model)
"""
from __future__ import annotations

import socket
import struct
import time
from dataclasses import dataclass, field

SERVICES = (
    "_adb-tls-pairing._tcp.local",
    "_adb-tls-connect._tcp.local",
    "_androidtvremote2._tcp.local",
    "_googlecast._tcp.local",
)
MDNS_ADDR, MDNS_PORT = "224.0.0.251", 5353
T_A, T_PTR, T_TXT, T_SRV = 1, 12, 16, 33


@dataclass
class Device:
    ip: str
    name: str = ""
    model: str = ""
    pairing_port: int = 0
    connect_port: int = 0
    tv_remote: bool = False
    cast: bool = False
    services: set = field(default_factory=set)

    @property
    def is_tv(self) -> bool:
        return self.tv_remote or self.cast or bool(self.connect_port) or bool(self.pairing_port)

    def label(self) -> str:
        bits = [self.name or "Unnamed device"]
        if self.model:
            bits.append(f"({self.model})")
        bits.append(f"at {self.ip}")
        return " ".join(bits)


def build_query(names=SERVICES, qid: int = 0) -> bytes:
    """A standard mDNS query with one PTR question per service name."""
    out = struct.pack("!HHHHHH", qid, 0, len(names), 0, 0, 0)
    for n in names:
        out += _encode_name(n) + struct.pack("!HH", T_PTR, 1)
    return out


def _encode_name(name: str) -> bytes:
    out = b""
    for label in name.strip(".").split("."):
        b = label.encode("utf-8")
        if not b or len(b) > 63:
            raise ValueError("bad DNS label")
        out += bytes([len(b)]) + b
    return out + b"\x00"


def _read_name(buf: bytes, off: int, depth: int = 0) -> tuple[str, int]:
    """Reads a (possibly compressed) DNS name; returns (name, offset after it)."""
    if depth > 10:
        raise ValueError("name loop")
    labels, end = [], None
    while True:
        if off >= len(buf):
            raise ValueError("truncated name")
        n = buf[off]
        if n == 0:
            off += 1
            break
        if n & 0xC0 == 0xC0:
            if off + 1 >= len(buf):
                raise ValueError("truncated pointer")
            ptr = ((n & 0x3F) << 8) | buf[off + 1]
            if ptr >= off:
                raise ValueError("forward pointer")
            rest, _ = _read_name(buf, ptr, depth + 1)
            labels.append(rest)
            off += 2
            end = off
            break
        off += 1
        labels.append(buf[off:off + n].decode("utf-8", "replace"))
        off += n
    return ".".join(x for x in labels if x), (end if end is not None else off)


def parse_packet(buf: bytes) -> list[tuple[str, int, object]]:
    """All answer, authority and additional records as (name, type, value). Malformed input -> []."""
    try:
        _, flags, qd, an, ns, ar = struct.unpack("!HHHHHH", buf[:12])
        off = 12
        for _ in range(qd):
            _, off = _read_name(buf, off)
            off += 4
        recs = []
        for _ in range(an + ns + ar):
            name, off = _read_name(buf, off)
            rtype, _cls, _ttl, rdlen = struct.unpack("!HHIH", buf[off:off + 10])
            off += 10
            rd = buf[off:off + rdlen]
            if len(rd) != rdlen:
                raise ValueError("truncated rdata")
            if rtype == T_PTR:
                val, _ = _read_name(buf, off)
            elif rtype == T_SRV:
                _prio, _w, port = struct.unpack("!HHH", rd[:6])
                target, _ = _read_name(buf, off + 6)
                val = (port, target)
            elif rtype == T_A and rdlen == 4:
                val = socket.inet_ntoa(rd)
            elif rtype == T_TXT:
                val, i = {}, 0
                while i < len(rd):
                    ln = rd[i]
                    s = rd[i + 1:i + 1 + ln].decode("utf-8", "replace")
                    if "=" in s:
                        k, v = s.split("=", 1)
                        val[k.lower()] = v
                    i += 1 + ln
            else:
                val = None
            recs.append((name.lower(), rtype, val))
            off += rdlen
        return recs
    except (ValueError, struct.error, IndexError):
        return []


def collect(records_by_sender: list[tuple[str, list]]) -> list[Device]:
    """Groups parsed records into devices, keyed by the IP that answered (or the A record)."""
    devices: dict[str, Device] = {}
    for sender, recs in records_by_sender:
        a_records = {n: v for n, t, v in recs if t == T_A}
        srv = {n: v for n, t, v in recs if t == T_SRV}
        txt = {n: v for n, t, v in recs if t == T_TXT}
        for name, rtype, val in recs:
            if rtype != T_PTR or not isinstance(val, str):
                continue
            service = name
            if service not in SERVICES:
                continue
            inst = val.lower()
            port, target = srv.get(inst, (0, ""))
            ip = a_records.get(target.lower(), sender) if target else sender
            d = devices.setdefault(ip, Device(ip=ip))
            d.services.add(service)
            t = txt.get(inst, {})
            if service.startswith("_adb-tls-pairing"):
                d.pairing_port = port
            elif service.startswith("_adb-tls-connect"):
                d.connect_port = port
            elif service.startswith("_androidtvremote2"):
                d.tv_remote = True
                d.name = d.name or val.split("._androidtvremote2")[0]
            elif service.startswith("_googlecast"):
                d.cast = True
                d.name = t.get("fn") or d.name
                d.model = t.get("md") or d.model
    return sorted(devices.values(), key=lambda d: (not d.tv_remote, d.ip))


def _local_ipv4() -> list[str]:
    ips = set()
    try:
        for a in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(a[4][0])
    except OSError:
        pass
    try:   # the address of the default route, in case the host name doesn't resolve to it
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("10.255.255.255", 1))
        ips.add(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith(("127.", "169.254.")))


def discover(timeout: float = 4.0) -> list[Device]:
    """Asks every local network (one query per interface, so VPN and virtual adapters don't
    swallow it) and returns what answered within {timeout} seconds."""
    import select
    ips = _local_ipv4()
    senders, socks = [], []
    for ip in ips:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(ip))
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
            s.bind((ip, 0))   # answers to a one-shot query come back unicast to this port
            s.setblocking(False)
            senders.append(s)
        except OSError:
            continue
    socks.extend(senders)
    try:   # also hear multicast answers (shared with the system's own mDNS service)
        lst = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        lst.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        lst.bind(("", MDNS_PORT))
        for ip in ips:
            try:
                lst.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, socket.inet_aton(MDNS_ADDR) + socket.inet_aton(ip))
            except OSError:
                pass
        lst.setblocking(False)
        socks.append(lst)
    except OSError:
        pass
    got: list[tuple[str, list]] = []
    q = build_query()
    end = time.time() + timeout
    next_send = 0.0
    try:
        while time.time() < end and socks:
            if time.time() >= next_send:
                for s in senders:
                    try:
                        s.sendto(q, (MDNS_ADDR, MDNS_PORT))
                    except OSError:
                        pass
                next_send = time.time() + 1.5
            ready, _, _ = select.select(socks, [], [], 0.3)
            for s in ready:
                try:
                    data, (ip, _port) = s.recvfrom(9000)
                except OSError:
                    continue
                recs = parse_packet(data)
                if recs:
                    got.append((ip, recs))
    finally:
        for s in socks:
            s.close()
    return collect(got)
