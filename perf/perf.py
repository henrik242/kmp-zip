#!/usr/bin/env python3
"""kmpzip vs gzip perf bench — Linux/macOS/Windows.

Usage:
    python3 perf/perf.py --kmpzip /path/to/kmpzip [--gzip gzip] \\
        [--sizes text:50M,rand:30M] [--trials 3] [--json-out perf.json]

Generates synthetic corpora, validates round-trip correctness in both directions
(kmpzip-compressed -> gzip-decompressed and gzip-compressed -> kmpzip-decompressed),
then runs N timed trials of compression and decompression and reports min wall-clock.
"""
import argparse
import filecmp
import json
import os
import platform
import shutil
import subprocess
import sys
import time
from pathlib import Path


SIZE_SUFFIX = {"K": 1024, "M": 1024**2, "G": 1024**3}


def parse_size(s: str) -> int:
    s = s.strip()
    if s[-1].upper() in SIZE_SUFFIX:
        return int(s[:-1]) * SIZE_SUFFIX[s[-1].upper()]
    return int(s)


def make_corpus(path: Path, kind: str, size: int) -> None:
    if path.exists() and path.stat().st_size == size:
        return
    chunk = 1 << 20  # 1 MiB
    if kind == "rand":
        with path.open("wb") as f:
            remaining = size
            while remaining > 0:
                n = min(chunk, remaining)
                f.write(os.urandom(n))
                remaining -= n
    elif kind == "zero":
        with path.open("wb") as f:
            buf = b"\x00" * chunk
            remaining = size
            while remaining > 0:
                n = min(chunk, remaining)
                f.write(buf[:n])
                remaining -= n
    elif kind == "text":
        # Repeating English-ish words. Try /usr/share/dict/words; fall back to a small list.
        words = None
        for cand in ("/usr/share/dict/words", "/usr/share/dict/american-english"):
            p = Path(cand)
            if p.exists():
                try:
                    words = [w.strip() for w in p.read_text(errors="replace").splitlines() if w.strip()]
                    if len(words) >= 100:
                        break
                except OSError:
                    pass
        if not words:
            words = (
                "the of and a to in is you that it he was for on are with as I his they be at "
                "one have this from or had by hot but some what there we can out other were all "
                "your when up use word how said an each she which do their time if will way about"
            ).split()
        block = (" ".join(words[:1000]) + "\n").encode()
        with path.open("wb") as f:
            written = 0
            while written < size:
                n = min(len(block), size - written)
                f.write(block[:n])
                written += n
    else:
        raise ValueError(f"unknown corpus kind: {kind}")


def time_run(argv, cwd=None) -> tuple[float, int, str]:
    t0 = time.perf_counter()
    r = subprocess.run(argv, cwd=cwd, stdout=subprocess.DEVNULL,
                       stderr=subprocess.PIPE)
    return time.perf_counter() - t0, r.returncode, r.stderr.decode("utf-8", "replace")


def bench_min(prep, argv, trials: int, cwd=None) -> tuple[float, int]:
    """Run prep() (untimed) then argv (timed) `trials` times. Return (min_secs, last_rc)."""
    best = None
    last_rc = 0
    for _ in range(trials):
        prep()
        t, rc, _ = time_run(argv, cwd=cwd)
        last_rc = rc
        if best is None or t < best:
            best = t
    return best or 0.0, last_rc


def warm_cache(path: Path) -> None:
    with path.open("rb") as f:
        while f.read(1 << 20):
            pass


def gzip_compress(gzip_bin: str, src: Path) -> Path:
    """Run `gzip -kf src` -> src.gz. Returns the .gz path."""
    out = src.with_suffix(src.suffix + ".gz")
    if out.exists():
        out.unlink()
    subprocess.run([gzip_bin, "-kf", str(src)], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return out


def gzip_decompress(gzip_bin: str, src_gz: Path) -> Path:
    """Run `gzip -df src.gz` -> src. Returns the decompressed path."""
    if str(src_gz).endswith(".gz"):
        out = Path(str(src_gz)[:-3])
    else:
        out = src_gz.with_suffix("")
    if out.exists():
        out.unlink()
    subprocess.run([gzip_bin, "-df", str(src_gz)], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return out


def kmpzip_compress(kmpzip_bin: str, src: Path) -> Path:
    out = src.with_suffix(src.suffix + ".gz")
    if out.exists():
        out.unlink()
    subprocess.run([kmpzip_bin, "gzip", str(src)], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return out


def kmpzip_decompress(kmpzip_bin: str, src_gz: Path) -> Path:
    if str(src_gz).endswith(".gz"):
        out = Path(str(src_gz)[:-3])
    else:
        out = src_gz.with_suffix("")
    if out.exists():
        out.unlink()
    subprocess.run([kmpzip_bin, "gunzip", str(src_gz)], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return out


def first_diff_offset(a: Path, b: Path) -> int | None:
    """Return offset of first byte difference, or None if identical (sizes also compared)."""
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


def correctness_check(work: Path, original: Path, gzip_bin: str, kmpzip_bin: str,
                       label: str) -> dict:
    """One round-trip pair: kmpzip-compress + gzip-decompress, AND gzip-compress + kmpzip-decompress.
    Returns dict with results."""
    results = {}

    # kmpzip → gzip
    k_in = work / f"k_{label}.dat"
    shutil.copy(original, k_in)
    try:
        k_gz = kmpzip_compress(kmpzip_bin, k_in)
        k_out = work / f"k_{label}_out.dat"
        if k_out.exists():
            k_out.unlink()
        # decompress to a side file using stdout: gzip -dc
        with k_out.open("wb") as f:
            subprocess.run([gzip_bin, "-dc", str(k_gz)], check=True,
                           stdout=f, stderr=subprocess.DEVNULL)
        diff = first_diff_offset(original, k_out)
        results["kz_to_gz"] = "OK" if diff is None else f"FAIL@{diff}"
    except Exception as e:
        results["kz_to_gz"] = f"ERROR: {e}"
    finally:
        for p in (k_in, k_in.with_suffix(k_in.suffix + ".gz"),
                   work / f"k_{label}_out.dat"):
            if p.exists():
                p.unlink()

    # gzip → kmpzip
    g_in = work / f"g_{label}.dat"
    shutil.copy(original, g_in)
    try:
        g_gz = gzip_compress(gzip_bin, g_in)
        # kmpzip writes the decompressed file as the .gz minus extension
        # but we want a stable side path: copy g_gz to t.gz, then kmpzip gunzip t.gz -> t
        t_gz = work / f"t_{label}.gz"
        shutil.copy(g_gz, t_gz)
        t_out = work / f"t_{label}"
        if t_out.exists():
            t_out.unlink()
        kmpzip_decompress(kmpzip_bin, t_gz)
        diff = first_diff_offset(original, t_out)
        results["gz_to_kz"] = "OK" if diff is None else f"FAIL@{diff}"
    except Exception as e:
        results["gz_to_kz"] = f"ERROR: {e}"
    finally:
        for p in (g_in, g_in.with_suffix(g_in.suffix + ".gz"),
                   work / f"t_{label}.gz", work / f"t_{label}"):
            if p.exists():
                p.unlink()

    return results


def fmt_secs(s: float) -> str:
    return f"{s:.3f}"


def fmt_bytes(n: int) -> str:
    return f"{n:,}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--kmpzip", required=True,
                    help="Path to kmpzip binary")
    ap.add_argument("--gzip", default="gzip",
                    help="Path to gzip binary (default: gzip on PATH)")
    ap.add_argument("--workdir", default="perf-work")
    ap.add_argument("--trials", type=int, default=3)
    ap.add_argument("--sizes", default="text:50M,rand:30M",
                    help="Comma-separated list of <kind>:<size>, where kind ∈ {text,rand,zero} "
                         "and size like 1M, 100M, 1G")
    ap.add_argument("--json-out", default=None,
                    help="Optional path to write structured JSON results")
    args = ap.parse_args()

    work = Path(args.workdir).resolve()
    work.mkdir(parents=True, exist_ok=True)

    gzip_bin = shutil.which(args.gzip) or args.gzip
    kmpzip_bin = str(Path(args.kmpzip).resolve())
    if not Path(kmpzip_bin).exists():
        print(f"ERROR: kmpzip binary not found at {kmpzip_bin}", file=sys.stderr)
        return 2

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

    def tool_version(bin_path: str) -> str:
        if not shutil.which(bin_path):
            return f"{bin_path} (not on PATH)"
        try:
            r = subprocess.run([bin_path, "--version"],
                               capture_output=True, text=True, timeout=5)
            text = (r.stdout or "") + (r.stderr or "")
            for line in text.splitlines():
                line = line.strip()
                if line:
                    return line
        except Exception as e:
            return f"version probe failed: {e}"
        return "unknown"

    out = {
        "env": {
            "platform": platform.platform(),
            "python": sys.version.split()[0],
            "machine": platform.machine(),
            "system": platform.system(),
            "gzip": tool_version(gzip_bin),
            "kmpzip_path": kmpzip_bin,
            "free_disk_bytes": shutil.disk_usage(work).free,
        },
        "trials": args.trials,
        "corpora": [{k: c[k] for k in ("name", "kind", "size")} for c in corpora],
        "correctness": {},
        "compression": {},
        "decompression": {},
    }

    print("=== Environment ===")
    for k, v in out["env"].items():
        print(f"  {k}: {v}")
    print()

    print("=== Generating corpora ===")
    for c in corpora:
        print(f"  {c['name']} ({fmt_bytes(c['size'])} bytes, {c['kind']})")
        make_corpus(c["path"], c["kind"], c["size"])
    print()

    print("=== Correctness ===")
    overall_ok = True
    for c in corpora:
        r = correctness_check(work, c["path"], gzip_bin, kmpzip_bin, c["name"])
        out["correctness"][c["name"]] = r
        ok = all(v == "OK" for v in r.values())
        overall_ok = overall_ok and ok
        print(f"  {c['name']}  kz→gz: {r['kz_to_gz']}   gz→kz: {r['gz_to_kz']}")
    print()

    print("=== Compression ===")
    print(f"  {'input':<20} {'orig':>14} {'gz_size':>14} {'kz_size':>14} "
          f"{'gz_secs':>10} {'kz_secs':>10} {'kz/gz':>8}")
    for c in corpora:
        src = c["path"]
        warm_cache(src)
        # gzip
        g_work = work / f"g_{c['name']}"
        def prep_g():
            shutil.copy(src, g_work)
            gz = g_work.with_suffix(g_work.suffix + ".gz")
            if gz.exists():
                gz.unlink()
        gz_t, _ = bench_min(prep_g, [gzip_bin, "-kf", str(g_work)], args.trials)
        gz_size = g_work.with_suffix(g_work.suffix + ".gz").stat().st_size
        # kmpzip
        k_work = work / f"k_{c['name']}"
        def prep_k():
            shutil.copy(src, k_work)
            gz = k_work.with_suffix(k_work.suffix + ".gz")
            if gz.exists():
                gz.unlink()
        kz_t, _ = bench_min(prep_k, [kmpzip_bin, "gzip", str(k_work)], args.trials)
        kz_size = k_work.with_suffix(k_work.suffix + ".gz").stat().st_size
        ratio = kz_t / gz_t if gz_t > 0 else float("nan")
        out["compression"][c["name"]] = {
            "orig_size": c["size"],
            "gzip": {"size": gz_size, "secs": gz_t},
            "kmpzip": {"size": kz_size, "secs": kz_t},
            "kz_over_gz": ratio,
        }
        print(f"  {c['name']:<20} {fmt_bytes(c['size']):>14} {fmt_bytes(gz_size):>14} "
              f"{fmt_bytes(kz_size):>14} {fmt_secs(gz_t):>10} {fmt_secs(kz_t):>10} "
              f"{ratio:>7.2f}x")
        # cleanup
        for p in (g_work, g_work.with_suffix(g_work.suffix + ".gz"),
                   k_work, k_work.with_suffix(k_work.suffix + ".gz")):
            if p.exists():
                p.unlink()
    print()

    print("=== Decompression ===")
    print(f"  {'input':<20} {'gz_in':>14} {'gz_secs':>10} {'kz_secs':>10} {'kz/gz':>8}")
    for c in corpora:
        src = c["path"]
        # Produce one canonical .gz with system gzip; both tools decompress it.
        ref = work / f"ref_{c['name']}"
        shutil.copy(src, ref)
        ref_gz = gzip_compress(gzip_bin, ref)
        gz_in = ref_gz.stat().st_size
        warm_cache(ref_gz)

        # gzip decomp
        g_gz = work / f"d_g_{c['name']}.gz"
        def prep_dg():
            shutil.copy(ref_gz, g_gz)
            out_path = work / f"d_g_{c['name']}"
            if out_path.exists():
                out_path.unlink()
        gz_d, _ = bench_min(prep_dg, [gzip_bin, "-df", str(g_gz)], args.trials)

        # kmpzip decomp
        k_gz = work / f"d_k_{c['name']}.gz"
        def prep_dk():
            shutil.copy(ref_gz, k_gz)
            out_path = work / f"d_k_{c['name']}"
            if out_path.exists():
                out_path.unlink()
        kz_d, _ = bench_min(prep_dk, [kmpzip_bin, "gunzip", str(k_gz)], args.trials)

        ratio = kz_d / gz_d if gz_d > 0 else float("nan")
        out["decompression"][c["name"]] = {
            "gz_in_size": gz_in,
            "gzip": {"secs": gz_d},
            "kmpzip": {"secs": kz_d},
            "kz_over_gz": ratio,
        }
        print(f"  {c['name']:<20} {fmt_bytes(gz_in):>14} "
              f"{fmt_secs(gz_d):>10} {fmt_secs(kz_d):>10} {ratio:>7.2f}x")

        # cleanup
        for p in (ref, ref_gz, g_gz, k_gz,
                   work / f"d_g_{c['name']}", work / f"d_k_{c['name']}"):
            if p.exists():
                p.unlink()
    print()

    if args.json_out:
        Path(args.json_out).write_text(json.dumps(out, indent=2, default=str))
        print(f"JSON results written to {args.json_out}")

    return 0 if overall_ok else 1


if __name__ == "__main__":
    sys.exit(main())
