"""Allow running Auxen with ``python -m auxen``."""

import os
import sys
import threading


def _install_stderr_filter() -> None:
    """Filter out harmless pixman_region32_init_rect messages from stderr.

    GTK4's overlay scrollbar rendering on fractional-HiDPI displays
    triggers a known pixman_region32_init_rect error inside
    gsk_render_node_draw.  The errors are cosmetic (no crash, no
    visual artefact) but noisy.  Since pixman writes directly to fd 2
    (not through Python's sys.stderr), we intercept at the fd level.

    The filter suppresses only the exact 3-line pixman error block::

        *** BUG ***
        In pixman_region32_init_rect: Invalid rectangle passed
        Set a breakpoint on '_pixman_log_error' to debug

    A ``*** BUG ***`` line that is NOT followed by the pixman-specific
    second line is passed through, so real bugs from other libraries
    are never hidden.  On any failure the filter restores fd 2 to the
    real stderr (fail-open).
    """
    try:
        read_fd, write_fd = os.pipe()
        real_stderr_fd = os.dup(2)
        os.dup2(write_fd, 2)
        os.close(write_fd)
    except OSError:
        return  # Can't set up pipe — skip filtering entirely

    # Reassign Python's sys.stderr to the new fd 2 so that Python-level
    # logging (which writes via sys.stderr, a TextIOWrapper) is also
    # flushed through the pipe correctly.
    sys.stderr = os.fdopen(2, "w", closefd=False)

    def _filter() -> None:
        try:
            buf = b""
            held_bug_line: bytes | None = None
            drop_next_breakpoint = False
            with os.fdopen(os.dup(real_stderr_fd), "wb") as dst:

                def _write(data: bytes) -> None:
                    dst.write(data)
                    dst.flush()

                def _flush_held() -> None:
                    nonlocal held_bug_line
                    if held_bug_line is not None:
                        _write(held_bug_line + b"\n")
                        held_bug_line = None

                while True:
                    try:
                        chunk = os.read(read_fd, 4096)
                    except OSError:
                        break
                    if not chunk:
                        _flush_held()
                        if buf:
                            _write(buf)
                        break
                    buf += chunk
                    while b"\n" in buf:
                        line, buf = buf.split(b"\n", 1)

                        # State machine: if we're holding a "*** BUG ***"
                        # line, check if the next line is the pixman msg.
                        if held_bug_line is not None:
                            if b"In pixman_region32_init_rect:" in line:
                                # Confirmed pixman block — drop both lines
                                held_bug_line = None
                                drop_next_breakpoint = True
                                continue
                            # Not pixman — flush the held line and
                            # process this line normally.
                            _flush_held()

                        # Is this the start of a potential pixman block?
                        if line.strip() == b"*** BUG ***":
                            held_bug_line = line
                            continue

                        # Drop the trailing "Set a breakpoint..." line
                        # only when it follows a confirmed pixman block.
                        if drop_next_breakpoint and (
                            b"Set a breakpoint on"
                            b" '_pixman_log_error'" in line
                        ):
                            drop_next_breakpoint = False
                            continue
                        drop_next_breakpoint = False

                        _write(line + b"\n")
        except Exception:
            # Fail-open: restore fd 2 to real stderr so nothing is lost
            try:
                os.dup2(real_stderr_fd, 2)
            except OSError:
                pass
        finally:
            try:
                os.close(read_fd)
            except OSError:
                pass
            try:
                os.close(real_stderr_fd)
            except OSError:
                pass

    t = threading.Thread(target=_filter, daemon=True)
    t.start()


import logging

from auxen.app import AuxenApp


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(levelname)s %(name)s: %(message)s",
    )
    _install_stderr_filter()
    app = AuxenApp()
    return app.run(sys.argv)


if __name__ == "__main__":
    raise SystemExit(main())
