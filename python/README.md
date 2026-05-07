# qupath-tiatoolbox (Python sidecar)

Python half of the TIAToolbox QuPath extension. Exposes tiatoolbox inference engines
to QuPath over a Py4J `ClientServer`. Not intended to be run by humans — the QuPath
extension launches it as a subprocess.

## Manual smoke test

```bash
conda activate tiatoolboxv2
python -m qupath_tiatoolbox --python-port 25334
# from another shell, you can connect with py4j JavaGateway/ClientServer on port 25334.
```

The process prints `READY port=<N>` to stdout once it accepts connections, and shuts
down cleanly when its parent dies (or when a Java client calls `shutdown()`).
