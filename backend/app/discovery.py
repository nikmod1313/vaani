"""
Announce this server on the shop's Wi-Fi.

Two names, by multicast DNS -- the way printers and Chromecasts are found:

* ``vaani.local`` -- a fixed address a person types into a browser,
  instead of an IP that changes whenever the router hands out a new one.
* a ``_vaani._tcp`` service -- what the phone looks for, so pairing needs
  only the six-digit code, never an address.

Nothing leaves the local network: multicast DNS does not cross a router.
Optional: without the ``zeroconf`` package, or with ``VAANI_MDNS=0``, the
server runs exactly as before and is reached by its IP.
"""

from __future__ import annotations

import logging
import os
import socket

log = logging.getLogger("vaani.discovery")

HOSTNAME = "vaani.local"
SERVICE = "_vaani._tcp.local."

_zc = None
_info = None


def lan_ip() -> str:
    """This machine's address on the local network."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))   # no packet is sent
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


async def start(port: int, shop_name: str = "Vaani") -> bool:
    """Called from the server's startup, inside its event loop -- hence async:
    the blocking zeroconf API refuses to run on a running loop."""
    global _zc, _info
    if os.environ.get("VAANI_MDNS", "1") == "0" or _zc is not None:
        return False
    try:
        from zeroconf import ServiceInfo
        from zeroconf.asyncio import AsyncZeroconf
    except ImportError:
        log.info("zeroconf not installed; reachable by IP only")
        return False
    ip = lan_ip()
    if ip.startswith("127."):
        return False
    zc = None
    try:
        _info = ServiceInfo(
            SERVICE,
            f"{shop_name}.{SERVICE}",
            addresses=[socket.inet_aton(ip)],
            port=port,
            properties={"path": "/", "app": "vaani"},
            server=f"{HOSTNAME}.",
        )
        zc = AsyncZeroconf()
        # allow_name_change: a second Vaani on the same Wi-Fi becomes
        # "Vaani (2)" instead of refusing to start.
        await zc.async_register_service(_info, allow_name_change=True)
        _zc = zc
        log.warning("announced http://%s:%d/ (%s)", HOSTNAME, port, ip)
        return True
    except Exception as e:  # noqa: BLE001 -- discovery is a convenience, never a reason to fail
        log.warning("could not announce on the network: %r", e)
        if zc is not None:
            await zc.async_close()
        return False


async def stop() -> None:
    global _zc
    if _zc is not None:
        try:
            await _zc.async_unregister_service(_info)
            await _zc.async_close()
        finally:
            _zc = None


def announced() -> bool:
    return _zc is not None
