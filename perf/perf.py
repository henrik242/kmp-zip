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
  - zip:  each --zip entry plus kmpzip's `create`/`extract` modes
"""
import argparse
import json
import os
import platform
import random
import shutil
import subprocess
import sys
import time
from pathlib import Path

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
        with path.open("wb") as f:
            remaining = size
            while remaining > 0:
                n = min(chunk, remaining)
                f.write(os.urandom(n))
                remaining -= n
    elif kind == "zero":
        buf = b"\x00" * chunk
        with path.open("wb") as f:
            remaining = size
            while remaining > 0:
                n = min(chunk, remaining)
                f.write(buf[:n])
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


def bench_min(prep, argv, trials: int, cwd=None) -> tuple[float, int, str]:
    best = None
    last_rc = 0
    last_err = ""
    for _ in range(trials):
        prep()
        t, rc, err = time_run(argv, cwd=cwd)
        last_rc, last_err = rc, err
        if best is None or t < best:
            best = t
    return best or 0.0, last_rc, last_err


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
        # kmpzip create takes archive then files; run from parent so entry is basename
        subprocess.run([kmpzip_bin, "create", str(archive.name), str(src.name)],
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
        subprocess.run([kmpzip_bin, "extract", "-d", str(out_dir), str(archive)],
                       check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        extracted = list(out_dir.iterdir())
        if len(extracted) != 1:
            raise RuntimeError(f"kmpzip extract produced {len(extracted)} entries (expected 1)")
        return extracted[0]

    def compress_argv(src: Path):
        archive = src.with_suffix(src.suffix + ".zip")
        return [kmpzip_bin, "create", str(archive.name), str(src.name)]

    def decompress_argv(archive: Path, out_dir: Path = None):
        return [kmpzip_bin, "extract", "-d", str(out_dir), str(archive)]

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

def _print_relative_summary(results: dict, corpora: list, tools: list, kind: str) -> None:
    """Per-corpus and mean speedup (compression: + size delta) vs the first tool.
    speed× > 1 means the tool is faster than the baseline; size% > 0 means its
    output is larger than the baseline's."""
    if len(tools) < 2:
        return
    base = tools[0]["name"]
    others = tools[1:]
    has_size = kind == "compression"

    def short(corpus_name: str) -> str:
        return corpus_name.rsplit(".", 1)[0]

    cell_w = max(len(short(c["name"])) for c in corpora) + 2
    label = "speed×/size%" if has_size else "speed×"
    print(f"  vs {base} ({kind}, {label}):")

    for t in others:
        speedups, size_deltas, cells = [], [], []
        for c in corpora:
            tdata = results.get(c["name"], {}).get("tools", {})
            bd, td = tdata.get(base, {}), tdata.get(t["name"], {})
            b_s, t_s = bd.get("secs", 0), td.get("secs", 0)
            cell = f"{short(c['name']):<{cell_w}}"
            if b_s > 0 and t_s > 0:
                sp = b_s / t_s
                speedups.append(sp)
                cell += f"{sp:5.2f}×"
            else:
                cell += "    —"
            if has_size:
                b_sz, t_sz = bd.get("size", 0), td.get("size", 0)
                if b_sz > 0 and t_sz > 0:
                    sd = (t_sz / b_sz - 1) * 100
                    size_deltas.append(sd)
                    cell += f"/{sd:+6.2f}%"
                else:
                    cell += "/      —"
            cells.append(cell)
        mean = f"{'mean':<{cell_w}}"
        mean += f"{sum(speedups)/len(speedups):5.2f}×" if speedups else "    —"
        if has_size:
            mean += f"/{sum(size_deltas)/len(size_deltas):+6.2f}%" if size_deltas else "/      —"
        print(f"    {t['name']:<8}" + "   ".join(cells) + f"   | {mean}")


def bench_compression(work: Path, corpora: list, tools: list, trials: int,
                       results: dict) -> None:
    """For each input, time each tool's compress on a fresh copy. Records size."""
    family = tools[0]["family"]
    name_w = max(len(c["name"]) for c in corpora) + 2
    print(f"=== Compression ({family}) ===")
    cols = ["input", "orig"]
    for t in tools:
        cols += [f"{t['name']}_size", f"{t['name']}_secs"]
    header = f"  {'input':<{name_w}} {'orig':>14}"
    for t in tools:
        header += f" {t['name']+'_size':>14} {t['name']+'_secs':>10}"
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
                if t["family"] == "gzip":
                    archive_path = work_src.with_suffix(work_src.suffix + ".gz")
                else:
                    archive_path = work_src.with_suffix(work_src.suffix + ".zip")
                if archive_path.exists():
                    archive_path.unlink()

            argv = t["compress_argv"](work_src)
            cwd = str(work_src.parent) if t["family"] == "zip" else None
            secs, rc, err = bench_min(prep, argv, trials, cwd=cwd)
            sz = archive_path.stat().st_size if archive_path and archive_path.exists() else -1
            per_input["tools"][t["name"]] = {"size": sz, "secs": secs, "rc": rc}
            line += f" {fmt_bytes(sz):>14} {fmt_secs(secs):>10}"
            # cleanup
            for p in (work_src, archive_path):
                if p and p.exists():
                    p.unlink()
        results[c["name"]] = per_input
        print(line)
    _print_relative_summary(results, corpora, tools, "compression")
    print()


def bench_decompression(work: Path, corpora: list, tools: list, trials: int,
                         results: dict) -> None:
    """Per input, produce one canonical archive with the FIRST tool, time each
    tool's decompress."""
    family = tools[0]["family"]
    name_w = max(len(c["name"]) for c in corpora) + 2
    print(f"=== Decompression ({family}) ===")
    header = f"  {'input':<{name_w}} {'archive_in':>14}"
    for t in tools:
        header += f" {t['name']+'_secs':>10}"
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

            secs, rc, err = bench_min(prep, argv, trials)
            per_input["tools"][t["name"]] = {"secs": secs, "rc": rc}
            line += f" {fmt_secs(secs):>10}"

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

        results[c["name"]] = per_input
        print(line)
    _print_relative_summary(results, corpora, tools, "decompression")
    print()


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


# ---- main --------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--kmpzip", required=True, help="Path to kmpzip binary")
    ap.add_argument("--gzip", action="append", default=[],
                    help="gzip tool: 'name=path' or just 'path'. Repeatable.")
    ap.add_argument("--zip", action="append", default=[], dest="zip_specs",
                    help="zip tool: 'name=zip_path' (unzip inferred as sibling). Repeatable.")
    ap.add_argument("--workdir", default="perf-work")
    ap.add_argument("--trials", type=int, default=3)
    ap.add_argument("--sizes", default="text:200M,rand:200M,zero:500M",
                    help="Comma-separated list of <kind>:<size>, kind in {text,rand,zero}")
    ap.add_argument("--json-out", default=None,
                    help="Optional path to write structured JSON results")
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
        "env": {
            "platform": platform.platform(),
            "python": sys.version.split()[0],
            "machine": platform.machine(),
            "system": platform.system(),
            "free_disk_bytes": shutil.disk_usage(work).free,
        },
        "trials": args.trials,
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
                          out["compression"][family])
        bench_decompression(work, corpora, tools, args.trials,
                            out["decompression"][family])

    if args.json_out:
        Path(args.json_out).write_text(json.dumps(out, indent=2, default=str))
        print(f"JSON results written to {args.json_out}")

    return 0 if overall_ok else 1


if __name__ == "__main__":
    sys.exit(main())
