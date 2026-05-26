"""CLI entry point.

Started as a subprocess by the QuPath extension::

    python -I -m qupath_tiatoolbox --python-port 25334

Boots a Py4J ClientServer with a :class:`TIATask` entry point on the given
port, prints ``READY port=<N>`` to stdout (the Java side waits for that
line), and blocks until either the Java client calls ``shutdown()`` or the
parent process dies.
"""

from __future__ import annotations

import argparse
import ctypes
import logging
import os
import platform
import signal
import sys
import threading
from ctypes import wintypes

from py4j.clientserver import ClientServer, JavaParameters, PythonParameters

from .bridge import TIATask

_STILL_ACTIVE = 259
_PROCESS_QUERY_LIMITED_INFORMATION = 0x1000


def _kernel32():
    kernel32 = ctypes.windll.kernel32
    kernel32.OpenProcess.argtypes = [
        wintypes.DWORD,
        wintypes.BOOL,
        wintypes.DWORD,
    ]
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.GetExitCodeProcess.argtypes = [
        wintypes.HANDLE,
        ctypes.POINTER(wintypes.DWORD),
    ]
    kernel32.GetExitCodeProcess.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.CloseHandle.restype = wintypes.BOOL
    return kernel32


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="qupath-tiatoolbox")
    p.add_argument(
        "--python-port",
        type=int,
        default=0,
        help="Port for the Python ClientServer to listen on (0 = auto).",
    )
    p.add_argument(
        "--java-port",
        type=int,
        default=0,
        help="Port of the Java ClientServer for callbacks (0 = auto/none).",
    )
    p.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
    )
    return p.parse_args(argv)


def _parent_alive(parent_pid: int) -> bool:
    """Return True while the original parent process is still running."""
    if parent_pid <= 0:
        return False

    if platform.system() != "Windows":
        try:
            os.kill(parent_pid, 0)
        except OSError:
            return False
        return True

    kernel32 = _kernel32()
    handle = kernel32.OpenProcess(
        _PROCESS_QUERY_LIMITED_INFORMATION, False, parent_pid
    )
    if not handle:
        return False
    try:
        exit_code = wintypes.DWORD()
        if not kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code)):
            return False
        return exit_code.value == _STILL_ACTIVE
    finally:
        kernel32.CloseHandle(handle)


def _watch_parent(parent_pid: int, shutdown: threading.Event) -> None:
    """Exit if the parent process disappears (orphaned subprocess guard)."""
    while not shutdown.wait(2.0):
        if not _parent_alive(parent_pid):
            shutdown.set()
            return


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    parent_pid = os.getppid()
    logging.basicConfig(
        level=args.log_level,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,
    )

    shutdown = threading.Event()
    task = TIATask(shutdown)

    java_params = JavaParameters(port=args.java_port) if args.java_port else JavaParameters()
    server = ClientServer(
        java_parameters=java_params,
        python_parameters=PythonParameters(port=args.python_port),
        python_server_entry_point=task,
    )

    listening_port = server.get_callback_server().get_listening_port()
    print(f"READY port={listening_port}", flush=True)
    logging.info("ClientServer listening on port %d", listening_port)

    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: shutdown.set())

    threading.Thread(target=_watch_parent, args=(parent_pid, shutdown), daemon=True).start()

    shutdown.wait()
    logging.info("Shutting down ClientServer")
    server.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
