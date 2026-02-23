"""Allow running Auxen with ``python -m auxen``."""

import sys

from auxen.app import AuxenApp


def main() -> int:
    app = AuxenApp()
    return app.run(sys.argv)


if __name__ == "__main__":
    raise SystemExit(main())
