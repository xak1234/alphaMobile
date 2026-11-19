#!/usr/bin/env python3
"""Build the pinned llama.cpp Android executable and collect its licence notices."""
import argparse
import os
import platform
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run(*command: str) -> None:
    subprocess.run(command, check=True, cwd=ROOT)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--abis", nargs="+", choices=["arm64-v8a", "x86_64"], default=["arm64-v8a", "x86_64"])
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    if args.sdk is None:
        parser.error("Set ANDROID_HOME or pass --sdk")
    sdk = args.sdk.resolve()
    suffix = ".exe" if os.name == "nt" else ""
    host = "windows-x86_64" if os.name == "nt" else "darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64"
    ndk = sdk / "ndk/28.2.13676358"
    cmake = sdk / ("cmake/3.31.6/bin/cmake" + suffix)
    ninja = sdk / ("cmake/3.31.6/bin/ninja" + suffix)
    source = ROOT / "vendor/llama.cpp"
    commit = (ROOT / "LLAMA_COMMIT").read_text().strip()
    if not source.exists():
        source.parent.mkdir(parents=True, exist_ok=True)
        run("git", "clone", "--filter=blob:none", "--no-checkout", "https://github.com/ggml-org/llama.cpp.git", str(source))
        run("git", "-C", str(source), "checkout", "--detach", commit)
    actual = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if actual != commit:
        raise RuntimeError("Existing llama.cpp checkout does not match LLAMA_COMMIT; preserve your changes before switching revisions")
    run("git", "-C", str(source), "diff", "--exit-code", "HEAD")
    for abi in args.abis:
        build = ROOT / ".native" / abi
        run(str(cmake), "-S", str(ROOT), "-B", str(build), "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=" + ninja.as_posix(),
            "-DCMAKE_TOOLCHAIN_FILE=" + (ndk / "build/cmake/android.toolchain.cmake").as_posix(),
            "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
        run(str(cmake), "--build", str(build), "--target", "alpha_server", "--parallel", str(args.jobs))
        output = ROOT / "android/local-model/src/main/jniLibs" / abi / "libalpha_server.so"
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(build / "libalpha_server.so", output)
        run(str(ndk / "toolchains/llvm/prebuilt" / host / ("bin/llvm-strip" + suffix)), "--strip-unneeded", str(output))
    notices = ROOT / "android/local-model/src/main/assets/notices"
    notices.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source / "LICENSE", notices / "llama.cpp-MIT.txt")
    for name in ["NOTICE", "NOTICE.toolchain"]:
        shutil.copy2(ndk / name, notices / ("Android-NDK-" + name + ".txt"))
    for base in [source / "vendor", source / "ggml"]:
        for path in base.rglob("*"):
            if path.is_file() and (path.name.upper().startswith("LICENSE") or path.name.upper().startswith("COPYING")):
                shutil.copy2(path, notices / ("_".join(path.relative_to(source).parts)))
    print("Native engines and notices ready.")


if __name__ == "__main__":
    main()
