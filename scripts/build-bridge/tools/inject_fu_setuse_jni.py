"""Re-inject and verify aarch64/arm trampolines with disassembly."""
from __future__ import annotations

import shutil
import struct
from pathlib import Path

import lief

PLUGIN_LIBS = (
    Path(__file__).resolve().parents[3]
    / "nativeplugins"
    / "FaceUnity-Nama"
    / "android"
    / "libs"
)

TARGETS = [
    "FUAI_FaceProcessorSetUseSkinSeg",
    "FUAI_FaceProcessorSetUseDelSpot",
    "FUAI_FaceProcessorSetUseFaceMeshV2",
]

JNI_NAMES = [
    "Java_com_faceunity_nama_FuAiExtras_nativeSetUseSkinSeg",
    "Java_com_faceunity_nama_FuAiExtras_nativeSetUseDelSpot",
    "Java_com_faceunity_nama_FuAiExtras_nativeSetUseFaceMeshV2",
]


def enc_b_aarch64(frm: int, to: int) -> int:
    imm26 = (to - frm) >> 2
    if not (-(1 << 25) <= imm26 < (1 << 25)):
        raise ValueError(f"branch too far: {frm:#x} -> {to:#x} imm={imm26}")
    return 0x14000000 | (imm26 & 0x03FFFFFF)


def enc_b_thumb2(frm: int, to: int) -> bytes:
    offset = to - frm - 4
    if offset % 2:
        raise ValueError("thumb branch misaligned")
    s = 1 if offset < 0 else 0
    imm = offset & ((1 << 25) - 1)
    imm10 = (imm >> 12) & 0x3FF
    imm11 = (imm >> 1) & 0x7FF
    j1 = 1 - (((imm >> 23) & 1) ^ s)
    j2 = 1 - (((imm >> 22) & 1) ^ s)
    h = 0xF000 | (s << 10) | imm10
    l = 0x9000 | (j1 << 13) | (j2 << 11) | imm11
    return struct.pack("<HH", h, l)


def patch_one(so_path: Path) -> None:
    bak = Path(str(so_path) + ".bak")
    if not bak.exists():
        shutil.copy2(so_path, bak)
    # Always start from clean backup
    shutil.copy2(bak, so_path)

    binary = lief.parse(str(so_path))
    assert binary is not None
    is64 = binary.header.machine_type == lief.ELF.ARCH.AARCH64
    tramp_size = 8
    total = tramp_size * len(TARGETS)

    targets = []
    for name in TARGETS:
        sym = binary.get_symbol(name)
        if sym is None:
            raise RuntimeError(f"missing {name}")
        targets.append(int(sym.value) & ~1)

    # Remove old tramp section if present
    if binary.has_section(".fu_jni_tramp"):
        binary.remove_section(".fu_jni_tramp", clear=True)

    for jni in JNI_NAMES:
        if binary.has_symbol(jni):
            binary.remove_symbol(jni)

    section = lief.ELF.Section(".fu_jni_tramp", lief.ELF.Section.TYPE.PROGBITS)
    section.content = list(b"\x00" * total)
    section.alignment = 16
    section.flags = lief.ELF.Section.FLAGS.ALLOC | lief.ELF.Section.FLAGS.EXECINSTR
    section = binary.add(section, loaded=True)
    base_va = int(section.virtual_address)

    stubs = bytearray()
    for i, (tgt, jni) in enumerate(zip(targets, JNI_NAMES)):
        va = base_va + i * tramp_size
        if is64:
            insn0 = 0x2A0203E0  # mov w0, w2
            insn1 = enc_b_aarch64(va + 4, tgt)
            stub = struct.pack("<II", insn0, insn1)
            print(f"  [{i}] va={va:#x} mov+b -> {tgt:#x} insn1={insn1:#x}")
        else:
            stub = struct.pack("<H", 0x4610) + struct.pack("<H", 0xBF00) + enc_b_thumb2(va + 4, tgt)
            print(f"  [{i}] va={va:#x} thumb -> {tgt:#x} stub={stub.hex()}")
        stubs.extend(stub)
        export_va = va if is64 else (va | 1)
        binary.add_exported_function(export_va, jni)

    section.content = list(stubs)
    # patch_address as belt-and-suspenders
    for i in range(len(TARGETS)):
        off = i * tramp_size
        binary.patch_address(base_va + off, list(stubs[off : off + tramp_size]))

    binary.write(str(so_path))

    check = lief.parse(str(so_path))
    sec = check.get_section(".fu_jni_tramp")
    raw = bytes(sec.content)
    print(f"wrote {so_path.name} tramp@{sec.virtual_address:#x} bytes={raw.hex()}")
    for jni, tgt in zip(JNI_NAMES, targets):
        s = check.get_symbol(jni)
        print(f"  {jni} => {s.value:#x} expect branch to {tgt:#x}")


def main() -> None:
    for abi in ("arm64-v8a", "armeabi-v7a"):
        path = PLUGIN_LIBS / abi / "libfuai.so"
        print("===", abi)
        patch_one(path)


if __name__ == "__main__":
    main()
