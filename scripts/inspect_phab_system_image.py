#!/usr/bin/env python3
import os
import struct
import sys


def unwrap(src, payload, diag):
    with open(src, 'rb') as f:
        header = f.read(64)
    with open(diag, 'w') as out:
        out.write('source=%s\n' % src)
        out.write('source_size=%d\n' % os.path.getsize(src))
        out.write('header_hex=%s\n' % header.hex())
        if header[:4] != b'BFBF':
            out.write('bfbf=false\n')
            return src
        version, header_size = struct.unpack_from('<II', header, 4)
        name = header[0x10:0x30].split(b'\0', 1)[0].decode('ascii', 'replace')
        out.write('bfbf=true\nversion=%d\nheader_size=%d\nname=%s\n' % (version, header_size, name))
        if header_size <= 0 or header_size >= os.path.getsize(src):
            raise RuntimeError('Invalid BFBF header size: %d' % header_size)
        with open(src, 'rb') as inp, open(payload, 'wb') as outp:
            inp.seek(header_size)
            while True:
                data = inp.read(16 * 1024 * 1024)
                if not data:
                    break
                outp.write(data)
        out.write('payload=%s\npayload_size=%d\n' % (payload, os.path.getsize(payload)))
        return payload


def scan_ext4(path, report, selection):
    size = os.path.getsize(path)
    hits = 0
    valid = []
    with open(path, 'rb') as f:
        chunk = 16 * 1024 * 1024
        tail = b''
        base = 0
        while base < size:
            data = f.read(min(chunk, size - base))
            if not data:
                break
            buf = tail + data
            start = base - len(tail)
            pos = 0
            while True:
                i = buf.find(b'\x53\xef', pos)
                if i < 0:
                    break
                pos = i + 2
                hits += 1
                magic = start + i
                sb = magic - 0x38
                fs = magic - 1024
                if sb < 0 or fs < 0 or fs % 1024:
                    continue
                f.seek(sb)
                s = f.read(0x200)
                if len(s) < 0x200 or s[0x38:0x3a] != b'\x53\xef':
                    continue
                inodes, blocks = struct.unpack_from('<II', s, 0)
                logbs = struct.unpack_from('<I', s, 24)[0]
                rev = struct.unpack_from('<I', s, 76)[0]
                inode_size = struct.unpack_from('<H', s, 88)[0]
                compat, incompat, ro = struct.unpack_from('<III', s, 92)
                if not inodes or not blocks or logbs > 6:
                    continue
                bs = 1024 << logbs
                if bs not in (1024, 2048, 4096) or inode_size < 128 or inode_size > bs:
                    continue
                blocks_hi = struct.unpack_from('<I', s, 0x150)[0]
                total = blocks | (blocks_hi << 32)
                length = total * bs
                if not total or length > size - fs:
                    continue
                valid.append((fs, bs, total, rev, compat, incompat, ro, inode_size, s[104:120].hex()))
            tail = data[-4096:]
            base += len(data)
    with open(report, 'w') as out:
        for v in valid:
            out.write('VALID_EXT4 filesystem_start=0x%x block_size=%d blocks=%d revision=%d compat=0x%08x incompat=0x%08x ro_compat=0x%08x inode_size=%d uuid=%s\n' % v)
        out.write('candidate_magic_count=%d\n' % hits)
        out.write('validated_ext4_count=%d\n' % len(valid))
    if valid:
        fs, bs, blocks = valid[0][:3]
        with open(selection, 'w') as out:
            out.write('%d %d %d\n' % (fs, bs, blocks))
    else:
        try:
            os.remove(selection)
        except FileNotFoundError:
            pass


def main():
    if len(sys.argv) != 5:
        raise SystemExit('usage: inspect_phab_system_image.py IMAGE PAYLOAD BFBF_REPORT EXT4_REPORT')
    src, payload, bfbf_report, ext4_report = sys.argv[1:]
    os.makedirs(os.path.dirname(payload), exist_ok=True)
    os.makedirs(os.path.dirname(bfbf_report), exist_ok=True)
    chosen = unwrap(src, payload, bfbf_report)
    scan_ext4(chosen, ext4_report, 'work/ext4-selection.txt')
    with open('work/image-path.txt', 'w') as out:
        out.write(chosen + '\n')


if __name__ == '__main__':
    main()
