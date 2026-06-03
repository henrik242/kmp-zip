#!/usr/bin/env python3
"""kmpzip vs system tools — perf bench (Linux/macOS/Windows).

Usage:
    python3 perf/perf.py \\
        --kmpzip /path/to/kmpzip \\
        --gzip apple=/usr/bin/gzip --gzip gnu=/opt/homebrew/bin/gzip \\
        --zip system=/usr/bin/zip \\
        [--sizes text:200M,rand:200M,zero:500M] [--trials 3] [--json-out perf.json]

Generates synthetic corpora, validates per-tool round-trip correctness, then
runs N timed trials of compress + decompress for each tool. Reports min
wall-clock. Two families:
  - gzip: each --gzip entry plus kmpzip's `gzip`/`gunzip` modes
  - zip:  each --zip entry plus kmpzip's `zip`/`unzip` modes

Also runs a "listing" benchmark: time to list entry names of a large many-entry
archive (plain + AES) via kmpzip `list` vs system unzip/zipinfo/7z, with kmpzip
`unzip` (full decode) as the baseline the listing path should beat.
"""
import argparse
import json
import os
import platform
import random
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

# Optional: enables peak-RSS sampling and richer env (total RAM). Without it the
# harness still runs; max_rss_bytes is reported as null. Install with
# `pip install psutil` (the CI workflow does this).
try:
    import psutil
except ImportError:
    psutil = None

# Windows console is cp1252 by default; force UTF-8 so any unicode in output
# (arrows, set-membership glyphs, etc.) doesn't crash on encoding.
for stream in (sys.stdout, sys.stderr):
    try:
        stream.reconfigure(encoding="utf-8")
    except (AttributeError, OSError):
        pass

SIZE_SUFFIX = {"K": 1024, "M": 1024**2, "G": 1024**3}


# ---- corpora -----------------------------------------------------------------

def parse_size(s: str) -> int:
    s = s.strip()
    if s and s[-1].upper() in SIZE_SUFFIX:
        return int(s[:-1]) * SIZE_SUFFIX[s[-1].upper()]
    return int(s)


def make_corpus(path: Path, kind: str, size: int) -> None:
    if path.exists() and path.stat().st_size == size:
        return
    chunk = 1 << 20
    if kind == "rand":
        # Seeded PRNG (not os.urandom) so the incompressible corpus is byte-identical
        # across platforms and runs — removes one source of cross-run variance.
        rng = random.Random(0x5EED)
        with path.open("wb") as f:
            remaining = size
            while remaining > 0:
                n = min(chunk, remaining)
                f.write(rng.randbytes(n))
                remaining -= n
    elif kind == "zero":
        buf = b"\x00" * chunk
        with path.open("wb") as f:
            remaining = size
            while remaining > 0:
                n = min(chunk, remaining)
                f.write(buf[:n])
                remaining -= n
    elif kind == "repeat":
        # A fixed 64 KiB random block tiled to size. Highly compressible like
        # `zero`, but via long back-references rather than the degenerate
        # everything-matches case — a more realistic match-finder workload.
        block = random.Random(0xBEEF).randbytes(64 * 1024)
        with path.open("wb") as f:
            remaining = size
            while remaining > 0:
                n = min(len(block), remaining)
                f.write(block[:n])
                remaining -= n
    elif kind == "text":
        # Seeded PRNG drawing from an English-frequency wordlist. Reproducible
        # across platforms — we used to read /usr/share/dict/words, which only
        # macOS ships in CI, so the text corpus differed across runners and
        # broke cross-platform comparison.
        words = (
            "the of and a to in is you that it he was for on are with as I his they be at "
            "one have this from or had by hot but some what there we can out other were all "
            "your when up use word how said an each she which do their time if will way about"
        ).split()
        tokens = [w.encode() for w in words]
        rng = random.Random(0xC0FFEE)
        with path.open("wb") as f:
            remaining = size
            while remaining > 0:
                buf = bytearray()
                while len(buf) < (1 << 16):
                    buf.extend(tokens[rng.randrange(len(tokens))])
                    buf.append(0x0A if rng.random() < 0.06 else 0x20)
                n = min(len(buf), remaining)
                f.write(bytes(buf[:n]))
                remaining -= n
    else:
        raise ValueError(f"unknown corpus kind: {kind}")


# ---- timing ------------------------------------------------------------------

def time_run(argv, cwd=None) -> tuple[float, int, str]:
    t0 = time.perf_counter()
    r = subprocess.run(argv, cwd=cwd,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    return time.perf_counter() - t0, r.returncode, r.stderr.decode("utf-8", "replace")


def _run_once(argv, cwd=None) -> tuple[float, int, str, int | None]:
    """One timed invocation. Returns (secs, rc, stderr, peak_rss_bytes). peak_rss
    is None unless psutil is available. Tools write little to stderr, so reading
    it after exit can't deadlock the small pipe."""
    if psutil is None:
        t, rc, err = time_run(argv, cwd=cwd)
        return t, rc, err, None
    t0 = time.perf_counter()
    proc = psutil.Popen(argv, cwd=cwd,
                        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    peak = 0

    def sample():
        nonlocal peak
        try:
            rss = proc.memory_info().rss
            for ch in proc.children(recursive=True):
                try:
                    rss += ch.memory_info().rss
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    pass
            if rss > peak:
                peak = rss
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass

    while proc.poll() is None:
        sample()
        time.sleep(0.005)
    sample()
    err = proc.stderr.read() if proc.stderr else b""
    secs = time.perf_counter() - t0
    return secs, proc.returncode, err.decode("utf-8", "replace"), (peak or None)


def measure(prep, argv, trials: int, nbytes: int = 0,
            warmup: int = 1, cwd=None) -> dict:
    """Run `warmup` discarded iterations, then `trials` timed ones. Keeps every
    trial (not just the min) plus median, throughput (on nbytes of data moved),
    and peak RSS, so version-over-version comparison has a stable absolute axis
    and regressions that widen variance aren't hidden by an unchanged floor."""
    last_rc, last_err = 0, ""
    for _ in range(max(0, warmup)):
        prep()
        _run_once(argv, cwd=cwd)
    times: list[float] = []
    peak = None
    for _ in range(trials):
        prep()
        t, rc, err, rss = _run_once(argv, cwd=cwd)
        times.append(t)
        last_rc, last_err = rc, err
        if rss is not None:
            peak = rss if peak is None else max(peak, rss)
    tmin = min(times) if times else 0.0
    tmed = statistics.median(times) if times else 0.0
    mb_s = (nbytes / (1024 * 1024) / tmin) if (nbytes and tmin > 0) else None
    return {
        "min": tmin,
        "median": tmed,
        "all": [round(x, 6) for x in times],
        "mb_s": round(mb_s, 2) if mb_s is not None else None,
        "max_rss_bytes": peak,
        "rc": last_rc,
        "err": last_err,
    }


def bench_min(prep, argv, trials: int, cwd=None) -> tuple[float, int, str]:
    """Back-compat shim for the listing bench: just the min time."""
    m = measure(prep, argv, trials, nbytes=0, cwd=cwd)
    return m["min"], m["rc"], m["err"]


def warm_cache(path: Path) -> None:
    with path.open("rb") as f:
        while f.read(1 << 20):
            pass


# ---- helpers -----------------------------------------------------------------

def first_diff_offset(a: Path, b: Path) -> int | None:
    sa, sb = a.stat().st_size, b.stat().st_size
    if sa != sb:
        return min(sa, sb)
    with a.open("rb") as fa, b.open("rb") as fb:
        offset = 0
        while True:
            ba = fa.read(1 << 16)
            bb = fb.read(1 << 16)
            if not ba and not bb:
                return None
            if ba != bb:
                for i, (x, y) in enumerate(zip(ba, bb)):
                    if x != y:
                        return offset + i
                return offset + min(len(ba), len(bb))
            offset += len(ba)


def fmt_secs(s: float) -> str:
    return f"{s:.3f}"


def fmt_bytes(n: int) -> str:
    return f"{n:,}"


def binary_present(p: str) -> bool:
    return bool(p) and (Path(p).exists() or shutil.which(p) is not None)


def tool_version(bin_path: str) -> str:
    if not binary_present(bin_path):
        return f"{bin_path} (not found)"
    try:
        # encoding="utf-8" so non-ASCII version strings (em dashes etc.)
        # don't get mojibaked under Windows' cp1252 default.
        r = subprocess.run([bin_path, "--version"],
                           capture_output=True, text=True, timeout=5,
                           encoding="utf-8", errors="replace")
        text = (r.stdout or "") + (r.stderr or "")
        for line in text.splitlines():
            line = line.strip()
            if line:
                return line
    except Exception as e:
        return f"version probe failed: {e}"
    return "unknown"


# ---- tool abstraction --------------------------------------------------------
# A "tool" is a compress + decompress pair for one family.
# Each tool exposes:
#   compress(src: Path) -> Path              # returns the produced archive
#   decompress(archive: Path, work: Path) -> Path  # returns the produced output file
# Plus a name and family.

def make_gzip_tool(name: str, gzip_bin: str) -> dict:
    """gzip-family tool: compress in-place, decompress writes file beside .gz"""
    def compress(src: Path) -> Path:
        archive = src.with_suffix(src.suffix + ".gz")
        if archive.exists():
            archive.unlink()
        subprocess.run([gzip_bin, "-kf", str(src)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return archive

    def decompress(archive: Path, work: Path) -> Path:
        # gzip -df strips .gz and produces sibling
        if archive.suffix != ".gz":
            raise ValueError(f"expected .gz file, got {archive}")
        out = archive.with_suffix("")
        if out.exists():
            out.unlink()
        subprocess.run([gzip_bin, "-df", str(archive)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return out

    def compress_argv(src: Path):
        return [gzip_bin, "-kf", str(src)]

    def decompress_argv(archive: Path):
        return [gzip_bin, "-df", str(archive)]

    return {"name": name, "family": "gzip", "bin": gzip_bin,
            "compress": compress, "decompress": decompress,
            "compress_argv": compress_argv, "decompress_argv": decompress_argv,
            "version": tool_version(gzip_bin)}


def make_kmpzip_gzip_tool(kmpzip_bin: str) -> dict:
    def compress(src: Path) -> Path:
        archive = src.with_suffix(src.suffix + ".gz")
        if archive.exists():
            archive.unlink()
        subprocess.run([kmpzip_bin, "gzip", str(src)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return archive

    def decompress(archive: Path, work: Path) -> Path:
        if archive.suffix != ".gz":
            raise ValueError(f"expected .gz file, got {archive}")
        out = archive.with_suffix("")
        if out.exists():
            out.unlink()
        subprocess.run([kmpzip_bin, "gunzip", str(archive)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return out

    def compress_argv(src: Path):
        return [kmpzip_bin, "gzip", str(src)]

    def decompress_argv(archive: Path):
        return [kmpzip_bin, "gunzip", str(archive)]

    return {"name": "kmpzip", "family": "gzip", "bin": kmpzip_bin,
            "compress": compress, "decompress": decompress,
            "compress_argv": compress_argv, "decompress_argv": decompress_argv,
            "version": tool_version(kmpzip_bin)}


def make_zip_tool(name: str, zip_bin: str, unzip_bin: str) -> dict:
    """zip-family tool: archive holds one entry (the basename of input).
    decompress extracts into a per-call output dir to avoid collisions."""
    def compress(src: Path) -> Path:
        archive = src.with_suffix(src.suffix + ".zip")
        if archive.exists():
            archive.unlink()
        # Run from src.parent so the entry stored is just src.name (no path prefix)
        subprocess.run([zip_bin, "-q", str(archive.name), str(src.name)],
                       check=True, cwd=str(src.parent),
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return archive

    def decompress(archive: Path, work: Path) -> Path:
        if archive.suffix != ".zip":
            raise ValueError(f"expected .zip file, got {archive}")
        out_dir = work / f"_extract_{archive.stem}"
        if out_dir.exists():
            shutil.rmtree(out_dir)
        out_dir.mkdir(parents=True)
        subprocess.run([unzip_bin, "-q", "-o", str(archive), "-d", str(out_dir)],
                       check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        # Find the (single) extracted file
        extracted = list(out_dir.iterdir())
        if len(extracted) != 1:
            raise RuntimeError(f"unzip produced {len(extracted)} entries (expected 1)")
        return extracted[0]

    def compress_argv(src: Path):
        archive = src.with_suffix(src.suffix + ".zip")
        return [zip_bin, "-q", str(archive.name), str(src.name)]

    def decompress_argv(archive: Path, out_dir: Path = None):
        # out_dir set by caller before timing
        return [unzip_bin, "-q", "-o", str(archive), "-d", str(out_dir)]

    return {"name": name, "family": "zip", "bin": zip_bin, "unzip_bin": unzip_bin,
            "compress": compress, "decompress": decompress,
            "compress_argv": compress_argv, "decompress_argv": decompress_argv,
            "version": tool_version(zip_bin)}


def make_kmpzip_zip_tool(kmpzip_bin: str) -> dict:
    def compress(src: Path) -> Path:
        archive = src.with_suffix(src.suffix + ".zip")
        if archive.exists():
            archive.unlink()
        # kmpzip zip takes archive then files; run from parent so entry is basename
        subprocess.run([kmpzip_bin, "zip", str(archive.name), str(src.name)],
                       check=True, cwd=str(src.parent),
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return archive

    def decompress(archive: Path, work: Path) -> Path:
        if archive.suffix != ".zip":
            raise ValueError(f"expected .zip file, got {archive}")
        out_dir = work / f"_extract_{archive.stem}"
        if out_dir.exists():
            shutil.rmtree(out_dir)
        out_dir.mkdir(parents=True)
        subprocess.run([kmpzip_bin, "unzip", "-d", str(out_dir), str(archive)],
                       check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        extracted = list(out_dir.iterdir())
        if len(extracted) != 1:
            raise RuntimeError(f"kmpzip unzip produced {len(extracted)} entries (expected 1)")
        return extracted[0]

    def compress_argv(src: Path):
        archive = src.with_suffix(src.suffix + ".zip")
        return [kmpzip_bin, "zip", str(archive.name), str(src.name)]

    def decompress_argv(archive: Path, out_dir: Path = None):
        return [kmpzip_bin, "unzip", "-d", str(out_dir), str(archive)]

    return {"name": "kmpzip", "family": "zip", "bin": kmpzip_bin,
            "compress": compress, "decompress": decompress,
            "compress_argv": compress_argv, "decompress_argv": decompress_argv,
            "version": tool_version(kmpzip_bin)}


# ---- correctness -------------------------------------------------------------

def correctness_for_tool(work: Path, original: Path, tool: dict, label: str) -> str:
    """Self-roundtrip: tool.compress + tool.decompress, byte-compare to original."""
    rt_in = work / f"rt_{tool['name']}_{label}.dat"
    if rt_in.exists():
        rt_in.unlink()
    shutil.copy(original, rt_in)
    archive = None
    out_path = None
    try:
        archive = tool["compress"](rt_in)
        out_path = tool["decompress"](archive, work)
        diff = first_diff_offset(original, out_path)
        return "OK" if diff is None else f"FAIL@{diff}"
    except subprocess.CalledProcessError as e:
        return f"ERROR(rc={e.returncode})"
    except Exception as e:
        return f"ERROR({type(e).__name__}: {e})"
    finally:
        for p in (rt_in, archive):
            if p and p.exists():
                p.unlink()
        if out_path and out_path.exists():
            out_path.unlink()
        # Cleanup any extract dirs
        for d in work.glob(f"_extract_*"):
            if d.is_dir():
                shutil.rmtree(d, ignore_errors=True)


# ---- bench drivers -----------------------------------------------------------

def _kmpzip_vs_cell(per_input: dict, other_name: str, has_size: bool, width: int) -> str:
    """One right-aligned cell showing kmpzip's speed× (and size%, for compression)
    relative to `other_name`. >1× speed = kmpzip faster; >0% size = kmpzip larger."""
    kdata = per_input.get("tools", {}).get("kmpzip", {})
    odata = per_input.get("tools", {}).get(other_name, {})
    k_s, o_s = kdata.get("secs", 0), odata.get("secs", 0)
    cell = f"{o_s / k_s:.2f}×" if k_s > 0 and o_s > 0 else "—"
    if has_size:
        k_sz, o_sz = kdata.get("size", 0), odata.get("size", 0)
        cell += f"/{(k_sz / o_sz - 1) * 100:+.2f}%" if k_sz > 0 and o_sz > 0 else "/—"
    return f"{cell:>{width}}"


def bench_compression(work: Path, corpora: list, tools: list, trials: int,
                       results: dict, warmup: int = 1) -> None:
    """For each input, time each tool's compress on a fresh copy. Records size."""
    family = tools[0]["family"]
    name_w = max(len(c["name"]) for c in corpora) + 2
    others = [t for t in tools if t["name"] != "kmpzip"]
    vs_w = 14
    print(f"=== Compression ({family}) ===")
    header = f"  {'input':<{name_w}} {'orig':>14}"
    for t in tools:
        header += f" {t['name']+'_size':>14} {t['name']+'_secs':>10}"
    for t in others:
        header += f" {('vs ' + t['name']):>{vs_w}}"
    print(header)

    for c in corpora:
        src = c["path"]
        warm_cache(src)
        per_input = {"orig_size": c["size"], "tools": {}}
        line = f"  {c['name']:<{name_w}} {fmt_bytes(c['size']):>14}"
        for t in tools:
            work_src = work / f"bc_{t['name']}_{c['name']}"
            archive_path = None

            def prep():
                nonlocal archive_path
                if work_src.exists():
                    work_src.unlink()
                shutil.copy(src, work_src)
                # Pre-fault the just-written copy so the tool's first read isn't
                # cold — on Windows runners this is where Defender's on-access
                # scan lands, and it scales with file size (worst on the 500M
                # corpus). Warming here keeps it out of the timed window.
                warm_cache(work_src)
                if t["family"] == "gzip":
                    archive_path = work_src.with_suffix(work_src.suffix + ".gz")
                else:
                    archive_path = work_src.with_suffix(work_src.suffix + ".zip")
                if archive_path.exists():
                    archive_path.unlink()

            argv = t["compress_argv"](work_src)
            cwd = str(work_src.parent) if t["family"] == "zip" else None
            m = measure(prep, argv, trials, nbytes=c["size"], warmup=warmup, cwd=cwd)
            sz = archive_path.stat().st_size if archive_path and archive_path.exists() else -1
            per_input["tools"][t["name"]] = {
                "size": sz, "secs": m["min"], "secs_median": m["median"],
                "secs_all": m["all"], "mb_s": m["mb_s"],
                "max_rss_bytes": m["max_rss_bytes"], "rc": m["rc"],
            }
            line += f" {fmt_bytes(sz):>14} {fmt_secs(m['min']):>10}"
            # cleanup
            for p in (work_src, archive_path):
                if p and p.exists():
                    p.unlink()
        for t in others:
            line += f" {_kmpzip_vs_cell(per_input, t['name'], has_size=True, width=vs_w)}"
        results[c["name"]] = per_input
        print(line)
    print()


def bench_decompression(work: Path, corpora: list, tools: list, trials: int,
                         results: dict, warmup: int = 1) -> None:
    """Per input, produce one canonical archive with the FIRST tool, time each
    tool's decompress."""
    family = tools[0]["family"]
    name_w = max(len(c["name"]) for c in corpora) + 2
    others = [t for t in tools if t["name"] != "kmpzip"]
    vs_w = 10
    print(f"=== Decompression ({family}) ===")
    header = f"  {'input':<{name_w}} {'archive_in':>14}"
    for t in tools:
        header += f" {t['name']+'_secs':>10}"
    for t in others:
        header += f" {('vs ' + t['name']):>{vs_w}}"
    print(header)

    canonical_tool = tools[0]
    for c in corpora:
        src = c["path"]
        ref_in = work / f"ref_{family}_{c['name']}"
        if ref_in.exists():
            ref_in.unlink()
        shutil.copy(src, ref_in)
        canonical_archive = canonical_tool["compress"](ref_in)
        archive_in_size = canonical_archive.stat().st_size
        warm_cache(canonical_archive)
        per_input = {"archive_in_size": archive_in_size, "canonical_tool": canonical_tool["name"], "tools": {}}
        line = f"  {c['name']:<{name_w}} {fmt_bytes(archive_in_size):>14}"

        for t in tools:
            work_arch = work / f"bd_{t['name']}_{c['name']}{canonical_archive.suffix}"
            out_dir = work / f"bd_extract_{t['name']}_{c['name']}"

            def prep():
                if work_arch.exists():
                    work_arch.unlink()
                shutil.copy(canonical_archive, work_arch)
                warm_cache(work_arch)
                # gzip removes input on decompress; zip extracts to a dir
                if t["family"] == "zip":
                    if out_dir.exists():
                        shutil.rmtree(out_dir)
                    out_dir.mkdir(parents=True)
                else:
                    sib = work_arch.with_suffix("")
                    if sib.exists():
                        sib.unlink()

            if t["family"] == "gzip":
                argv = t["decompress_argv"](work_arch)
            else:
                argv = t["decompress_argv"](work_arch, out_dir)

            m = measure(prep, argv, trials, nbytes=c["size"], warmup=warmup)
            per_input["tools"][t["name"]] = {
                "secs": m["min"], "secs_median": m["median"],
                "secs_all": m["all"], "mb_s": m["mb_s"],
                "max_rss_bytes": m["max_rss_bytes"], "rc": m["rc"],
            }
            line += f" {fmt_secs(m['min']):>10}"

            # cleanup
            if work_arch.exists():
                work_arch.unlink()
            sib = work_arch.with_suffix("")
            if sib.exists():
                sib.unlink()
            if out_dir.exists():
                shutil.rmtree(out_dir, ignore_errors=True)

        # cleanup canonical
        for p in (ref_in, canonical_archive):
            if p.exists():
                p.unlink()

        for t in others:
            line += f" {_kmpzip_vs_cell(per_input, t['name'], has_size=False, width=vs_w)}"
        results[c["name"]] = per_input
        print(line)
    print()


# ---- listing benchmark -------------------------------------------------------
# The reported issue: walking entries to list/select files in a large (encrypted)
# archive should not decrypt or inflate the data you skip. kmpzip `list` reads the
# central directory (ZipFile); unzip -Z1 / zipinfo / 7z l do the same. Full decode
# (kmpzip `unzip`) is shown alongside as the cost the listing path avoids.

# Listing reads the central directory, so no password is needed (filenames aren't
# encrypted) — the argv builders take only the archive path.
def make_list_tools(kmpzip_bin: str) -> list[dict]:
    tools = [{"name": "kmpzip list", "own": True, "argv": lambda arc: [kmpzip_bin, "list", str(arc)]}]
    if binary_present("unzip"):
        tools.append({"name": "unzip -Z1", "argv": lambda arc: ["unzip", "-Z1", str(arc)]})
    if binary_present("zipinfo"):
        tools.append({"name": "zipinfo -1", "argv": lambda arc: ["zipinfo", "-1", str(arc)]})
    sevenzip = next((b for b in ("7zz", "7za", "7z") if binary_present(b)), None)
    if sevenzip:
        tools.append({"name": f"{sevenzip} l", "argv": lambda arc: [sevenzip, "l", "-ba", str(arc)]})
    return tools


def bench_listing(work: Path, kmpzip_bin: str, trials: int, out: dict) -> bool:
    """Times 'list entry names' on a many-entry archive — plain and AES — for kmpzip
    vs system tools, with full decode (kmpzip unzip) as the baseline it should beat."""
    entry_count, entry_size = 200, 256 * 1024  # ~50MB of incompressible random media (deflate is a no-op, so listing cost dominates)
    src_dir = work / "list_src"
    src_dir.mkdir(parents=True, exist_ok=True)
    names = []
    for i in range(entry_count):
        p = src_dir / f"media_{i:04d}.bin"
        if not (p.exists() and p.stat().st_size == entry_size):
            p.write_bytes(os.urandom(entry_size))
        names.append(p.name)

    print(f"=== Listing ({entry_count} entries, {fmt_bytes(entry_count * entry_size)}) ===")
    list_tools = make_list_tools(kmpzip_bin)
    ok = True
    for variant, pw in (("plain", None), ("encrypted-aes", "benchpass")):
        archive = work / f"list_{variant}.zip"
        if archive.exists():
            archive.unlink()
        build = [kmpzip_bin, "zip"] + (["-p", pw] if pw else []) + [str(archive)] + names
        r = subprocess.run(build, cwd=str(src_dir),
                           stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        if r.returncode != 0:
            print(f"  {variant}: archive build failed (rc={r.returncode}): "
                  f"{r.stderr.decode('utf-8', 'replace')[:200]}")
            ok = False
            continue

        warm_cache(archive)
        print(f"  -- {variant} --")
        row = {}
        for t in list_tools:
            secs, rc, err = bench_min(lambda: None, t["argv"](archive), trials)
            row[t["name"]] = {"secs": secs, "rc": rc}
            # Only kmpzip's own commands gate success; third-party tools are
            # informational (e.g. unzip returns nonzero on benign warnings).
            if rc != 0 and t.get("own"):
                ok = False
            print(f"    {t['name']:<16} {fmt_secs(secs)}" + ("" if rc == 0 else f"  (rc={rc})"))
        # Baseline: full decode (what listing must NOT cost). Extract to a temp dir.
        out_dir = work / f"list_extract_{variant}"
        def prep():
            if out_dir.exists():
                shutil.rmtree(out_dir)
            out_dir.mkdir(parents=True)
        decode_argv = [kmpzip_bin, "unzip"] + (["-p", pw] if pw else []) + ["-d", str(out_dir), str(archive)]
        secs, rc, err = bench_min(prep, decode_argv, max(1, trials // 2))
        row["kmpzip unzip (decode all)"] = {"secs": secs, "rc": rc}
        print(f"    {'kmpzip unzip*':<16} {fmt_secs(secs)}  (*full decode baseline)")
        shutil.rmtree(out_dir, ignore_errors=True)
        out.setdefault("listing", {})[variant] = row
    print("  (sub-10ms rows sit near the process-startup floor; the decode baseline is the"
          " meaningful contrast — listing must not pay it)")
    print()
    return ok


# ---- argparse parsing helpers -----------------------------------------------

def parse_named_path(spec: str, default_name: str = None) -> tuple[str, str]:
    """Parse 'name=path' or just 'path' (name defaults to provided default or basename)."""
    if "=" in spec:
        name, path = spec.split("=", 1)
        return name.strip(), path.strip()
    return (default_name or Path(spec).name or "tool"), spec.strip()


def parse_zip_spec(spec: str) -> tuple[str, str, str]:
    """Parse 'name=zip_path'; the unzip path is the sibling whose basename is
    `zip_path`'s with the first 'zip' rewritten to 'unzip' (so /usr/bin/zip ->
    /usr/bin/unzip, C:/foo/zip.exe -> C:/foo/unzip.exe, bsdzip -> bsdunzip).

    An empty zip_p ('--zip system=' from a runner where `command -v zip` found
    nothing) is passed through unchanged — the binary_present filter in main()
    will drop the tool with a warning."""
    name, zip_p = parse_named_path(spec, default_name="zip")
    zip_p = zip_p.strip()
    zp = Path(zip_p)
    if not zp.name:
        return name, zip_p, ""
    unzip_p = str(zp.with_name(zp.name.replace("zip", "unzip", 1)))
    return name, zip_p, unzip_p


# ---- environment & baseline --------------------------------------------------

def _cpu_brand() -> str:
    try:
        if sys.platform.startswith("linux"):
            for line in Path("/proc/cpuinfo").read_text().splitlines():
                if line.lower().startswith("model name"):
                    return line.split(":", 1)[1].strip()
        elif sys.platform == "darwin":
            r = subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"],
                               capture_output=True, text=True, timeout=5)
            if r.returncode == 0 and r.stdout.strip():
                return r.stdout.strip()
    except Exception:
        pass
    return platform.processor() or os.environ.get("PROCESSOR_IDENTIFIER", "") or "unknown"


def collect_env(work: Path) -> dict:
    """Environment block. Records enough (CPU, cores, RAM, runner image) to
    explain a 'slow this run' after the fact instead of guessing."""
    env = {
        "platform": platform.platform(),
        "python": sys.version.split()[0],
        "machine": platform.machine(),
        "system": platform.system(),
        "cpu": _cpu_brand(),
        "logical_cpus": os.cpu_count(),
        "total_ram_bytes": psutil.virtual_memory().total if psutil else None,
        # GitHub runners set these; harmless empties off-CI.
        "runner_image": os.environ.get("ImageOS", "") or os.environ.get("ImageVersion", ""),
        "free_disk_bytes": shutil.disk_usage(work).free,
        "psutil": bool(psutil),
    }
    return env


def compare_to_baseline(out: dict, baseline_path: str, threshold: float) -> bool:
    """Warn-only throughput comparison of kmpzip against a prior JSON result.
    Returns False if any kmpzip throughput dropped by more than `threshold`
    (e.g. 0.10 = 10%); the caller decides whether to treat that as fatal."""
    try:
        base = json.loads(Path(baseline_path).read_text())
    except (OSError, ValueError) as e:
        print(f"WARN: could not read baseline {baseline_path!r}: {e}", file=sys.stderr)
        return True
    print(f"=== Regression check vs baseline ({baseline_path}) ===")
    print(f"  kmpzip throughput, this run vs baseline (threshold {threshold:.0%})")
    ok = True
    for op in ("compression", "decompression"):
        for family in ("gzip", "zip"):
            cur = out.get(op, {}).get(family, {})
            old = base.get(op, {}).get(family, {})
            for cname, cur_row in cur.items():
                new_mb = cur_row.get("tools", {}).get("kmpzip", {}).get("mb_s")
                old_mb = old.get(cname, {}).get("tools", {}).get("kmpzip", {}).get("mb_s")
                if not new_mb or not old_mb:
                    continue
                delta = new_mb / old_mb - 1.0
                flag = ""
                if delta < -threshold:
                    flag = "  <-- REGRESSION"
                    ok = False
                print(f"  {op[:4]}/{family:<4} {cname:<16} "
                      f"{old_mb:8.1f} -> {new_mb:8.1f} MB/s  {delta:+6.1%}{flag}")
    print()
    return ok


# ---- main --------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--kmpzip", required=True, help="Path to kmpzip binary")
    ap.add_argument("--gzip", action="append", default=[],
                    help="gzip tool: 'name=path' or just 'path'. Repeatable.")
    ap.add_argument("--zip", action="append", default=[], dest="zip_specs",
                    help="zip tool: 'name=zip_path' (unzip inferred as sibling). Repeatable.")
    ap.add_argument("--workdir", default="perf-work")
    ap.add_argument("--trials", type=int, default=5,
                    help="Timed trials per measurement (min reported; all kept in JSON)")
    ap.add_argument("--warmup", type=int, default=1,
                    help="Discarded warmup runs before timing (absorbs cold-cache/page-fault cost)")
    ap.add_argument("--sizes", default="text:200M,rand:200M,zero:500M",
                    help="Comma-separated <kind>:<size>, kind in {text,rand,zero,repeat}")
    ap.add_argument("--json-out", default=None,
                    help="Optional path to write structured JSON results")
    ap.add_argument("--baseline", default=None,
                    help="Optional prior JSON result to compare kmpzip throughput against")
    ap.add_argument("--regression-threshold", type=float, default=0.10,
                    help="Throughput drop fraction that counts as a regression (default 0.10)")
    ap.add_argument("--fail-on-regression", action="store_true",
                    help="Exit non-zero if --baseline shows a throughput regression")
    args = ap.parse_args()

    work = Path(args.workdir).resolve()
    work.mkdir(parents=True, exist_ok=True)

    kmpzip_bin = str(Path(args.kmpzip).resolve())
    if not Path(kmpzip_bin).exists():
        print(f"ERROR: kmpzip binary not found at {kmpzip_bin}", file=sys.stderr)
        return 2

    # Skip (with a warning) any reference tool whose binary isn't present —
    # e.g. choco didn't install zip on the runner. kmpzip itself is always
    # included; we already verified its binary above.
    gzip_tools = []
    for spec in args.gzip:
        name, path = parse_named_path(spec, default_name="gzip")
        resolved = shutil.which(path) or path
        if not binary_present(resolved):
            print(f"WARN: gzip tool {name!r} not found at {path!r}; skipping",
                  file=sys.stderr)
            continue
        gzip_tools.append(make_gzip_tool(name, resolved))
    gzip_tools.append(make_kmpzip_gzip_tool(kmpzip_bin))

    zip_tools = []
    for spec in args.zip_specs:
        name, zp, up = parse_zip_spec(spec)
        zp_r = shutil.which(zp) or zp
        up_r = shutil.which(up) or up
        if not binary_present(zp_r) or not binary_present(up_r):
            print(f"WARN: zip tool {name!r} not found "
                  f"(zip={zp!r}, unzip={up!r}); skipping",
                  file=sys.stderr)
            continue
        zip_tools.append(make_zip_tool(name, zp_r, up_r))
    zip_tools.append(make_kmpzip_zip_tool(kmpzip_bin))

    # Build corpus list
    corpora = []
    for spec in args.sizes.split(","):
        spec = spec.strip()
        if not spec:
            continue
        kind, sz = spec.split(":")
        size = parse_size(sz)
        name = f"{kind}_{sz}.dat"
        corpora.append({"name": name, "kind": kind, "size": size,
                         "path": work / name})

    out = {
        "env": collect_env(work),
        "trials": args.trials,
        "warmup": args.warmup,
        "corpora": [{k: c[k] for k in ("name", "kind", "size")} for c in corpora],
        "tools": {
            "gzip": [{"name": t["name"], "version": t["version"], "bin": t["bin"]}
                      for t in gzip_tools],
            "zip":  [{"name": t["name"], "version": t["version"], "bin": t["bin"]}
                      for t in zip_tools],
        },
        "correctness": {"gzip": {}, "zip": {}},
        "compression":  {"gzip": {}, "zip": {}},
        "decompression": {"gzip": {}, "zip": {}},
    }

    print("=== Environment ===")
    for k, v in out["env"].items():
        print(f"  {k}: {v}")
    print(f"  gzip tools:")
    for t in gzip_tools:
        print(f"    - {t['name']}: {t['bin']} ({t['version']})")
    print(f"  zip tools:")
    for t in zip_tools:
        print(f"    - {t['name']}: {t['bin']} ({t['version']})")
    print()

    print("=== Generating corpora ===")
    for c in corpora:
        print(f"  {c['name']} ({fmt_bytes(c['size'])} bytes, {c['kind']})")
        make_corpus(c["path"], c["kind"], c["size"])
    print()

    overall_ok = True

    # --- correctness ----------------------------------------------------------
    for family, tools in (("gzip", gzip_tools), ("zip", zip_tools)):
        print(f"=== Correctness ({family}) ===")
        for c in corpora:
            row = {}
            for t in tools:
                r = correctness_for_tool(work, c["path"], t, c["name"])
                row[t["name"]] = r
                if r != "OK":
                    overall_ok = False
            out["correctness"][family][c["name"]] = row
            label = "  ".join(f"{n}: {r}" for n, r in row.items())
            print(f"  {c['name']:<20} {label}")
        print()

    # --- compression / decompression -----------------------------------------
    for family, tools in (("gzip", gzip_tools), ("zip", zip_tools)):
        bench_compression(work, corpora, tools, args.trials,
                          out["compression"][family], warmup=args.warmup)
        bench_decompression(work, corpora, tools, args.trials,
                            out["decompression"][family], warmup=args.warmup)

    # --- listing (the "iterate entries is slow" issue) ------------------------
    if not bench_listing(work, kmpzip_bin, args.trials, out):
        overall_ok = False

    if args.json_out:
        Path(args.json_out).write_text(json.dumps(out, indent=2, default=str))
        print(f"JSON results written to {args.json_out}")

    if args.baseline:
        if not compare_to_baseline(out, args.baseline, args.regression_threshold):
            if args.fail_on_regression:
                overall_ok = False

    return 0 if overall_ok else 1


if __name__ == "__main__":
    sys.exit(main())
