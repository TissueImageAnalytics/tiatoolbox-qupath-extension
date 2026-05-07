"""CLI entry point.

Started as a subprocess by the QuPath extension::

    python -m qupath_tiatoolbox --python-port 25334

Boots a Py4J ClientServer with a :class:`TIATask` entry point on the given
port, prints ``READY port=<N>`` to stdout (the Java side waits for that
line), and blocks until either the Java client calls ``shutdown()`` or the
parent process dies.
"""

from __future__ import annotations

import argparse
import logging
import os
import signal
import sys
import threading

from py4j.clientserver import ClientServer, JavaParameters, PythonParameters

from .bridge import TIATask


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


def _watch_parent(parent_pid: int, shutdown: threading.Event) -> None:
    """Exit if the parent process disappears (orphaned subprocess guard)."""
    while not shutdown.wait(2.0):
        try:
            os.kill(parent_pid, 0)
        except OSError:
            shutdown.set()
            return


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
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

    parent_pid = os.getppid()
    threading.Thread(target=_watch_parent, args=(parent_pid, shutdown), daemon=True).start()

    shutdown.wait()
    logging.info("Shutting down ClientServer")
    server.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
