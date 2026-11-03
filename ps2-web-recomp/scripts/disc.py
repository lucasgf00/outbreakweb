import argparse
import fnmatch
import os
import struct
import sys

SECTOR = 2048


class Layer:
    def __init__(self, index, base, pvd):
        self.index = index
        self.base = base
        self.volume = pvd[40:72].decode('ascii', 'replace').strip()
        self.sectors = struct.unpack_from('<I', pvd, 80)[0]
        self.root = struct.unpack_from('<I', pvd, 158)[0], struct.unpack_from('<I', pvd, 166)[0]


class Image:
    def __init__(self, path):
        self.file = open(path, 'rb')
        self.size = os.path.getsize(path)
        self.layers = [Layer(0, 0, self.pvd(16))]
        first = self.layers[0].sectors
        for sector in range(first, min(first + 64, self.size // SECTOR)):
            if self.read(sector, 1)[:6] == b'\x01CD001':
                self.layers.append(Layer(1, sector - 16, self.read(sector, 1)))
                break

    def read(self, sector, count):
        self.file.seek(sector * SECTOR)
        return self.file.read(count * SECTOR)

    def pvd(self, sector):
        data = self.read(sector, 1)
        if data[:6] != b'\x01CD001':
            sys.exit('not an ISO 9660 image: no primary volume descriptor at sector %d' % sector)
        return data

    def files(self):
        for layer in self.layers:
            yield from self.walk(layer, layer.root[0], layer.root[1], '')

    def walk(self, layer, lba, size, prefix):
        data = self.read(layer.base + lba, (size + SECTOR - 1) // SECTOR)
        offset = 0
        while offset < size:
            length = data[offset]
            if length == 0:
                offset = (offset // SECTOR + 1) * SECTOR
                continue
            record = data[offset:offset + length]
            offset += length
            extent, extent_size = struct.unpack_from('<I', record, 2)[0], struct.unpack_from('<I', record, 10)[0]
            name = record[33:33 + record[32]]
            if name in (b'\x00', b'\x01'):
                continue
            name = name.decode('ascii', 'replace').split(';')[0]
            path = prefix + name
            if record[25] & 2:
                yield from self.walk(layer, extent, extent_size, path + '/')
            else:
                yield path, layer, layer.base + extent, extent_size

    def find(self, path):
        wanted = path.replace('\\', '/').strip('/').upper()
        for entry in self.files():
            if entry[0].upper() == wanted:
                return entry
        return None

    def copy(self, entry, destination):
        path, _, sector, size = entry
        os.makedirs(os.path.dirname(destination) or '.', exist_ok=True)
        self.file.seek(sector * SECTOR)
        with open(destination, 'wb') as out:
            left = size
            while left:
                chunk = self.file.read(min(left, 1 << 22))
                out.write(chunk)
                left -= len(chunk)


def info(image):
    for layer in image.layers:
        print('layer %d: volume %s, base sector %d, %d sectors' % (layer.index, layer.volume, layer.base, layer.sectors))
    entry = image.find('SYSTEM.CNF')
    if entry:
        image.file.seek(entry[2] * SECTOR)
        print(image.file.read(entry[3]).decode('ascii', 'replace').strip())


def listing(image):
    for path, layer, sector, size in image.files():
        print('%d %10d %12d  %s' % (layer.index, sector, size, path))


def extract(image, destination, patterns, limit):
    count = 0
    for entry in image.files():
        path, _, _, size = entry
        if patterns and not any(fnmatch.fnmatch(path.upper(), p.upper()) for p in patterns):
            continue
        if not patterns and limit and size > limit:
            print('left in the image: %s (%d MiB)' % (path, size >> 20))
            continue
        image.copy(entry, os.path.join(destination, path))
        count += 1
    print('%d files extracted to %s' % (count, destination))


def read_at(image, sector, offset, size):
    image.file.seek(sector * SECTOR + offset)
    return image.file.read(size)


def modules(image, destination):
    found = []
    for path, _, sector, size in image.files():
        head = read_at(image, sector, 0, 8)
        if head[:4] == b'SNR2':
            found.append((path.replace('/', '_'), sector, 0, size))
        elif head[:4] == b'AFS\0':
            count = struct.unpack_from('<I', head, 4)[0]
            table = read_at(image, sector, 8, count * 8)
            for index in range(count):
                offset, length = struct.unpack_from('<II', table, index * 8)
                if offset + 4 <= size and read_at(image, sector, offset, 4) == b'SNR2':
                    found.append(('%s_%04d' % (path.replace('/', '_'), index), sector, offset, length))
    os.makedirs(destination, exist_ok=True)
    for name, sector, offset, length in found:
        with open(os.path.join(destination, name + '.snr'), 'wb') as out:
            out.write(read_at(image, sector, offset, length))
    print('%d SNR2 modules extracted to %s' % (len(found), destination))


def capcom_lz(data):
    """Capcom's 16-bit LZ, as decoded at 0x001AF5D0 in SLUS-20765 (Resident Evil Outbreak).

    A flag halfword covers the next 16 items, most significant bit first. A clear bit copies one
    halfword. A set bit reads a token halfword: count = token >> 11 and distance = token & 0x7FF, or,
    when that count is zero, distance = token and count = the next halfword. Distance zero writes
    count zero halfwords, and ends the stream when count is zero as well; otherwise count halfwords
    are copied from distance halfwords back. Returns None for data that does not decode cleanly."""
    out = bytearray()
    p, flags, bit, end = 0, 0, 0, len(data) - 1
    while p < end:
        if not bit:
            flags, bit = data[p] | data[p + 1] << 8, 0x8000
            p += 2
            if p >= end:
                return None
        if flags & bit:
            token = data[p] | data[p + 1] << 8
            p += 2
            count, distance = token >> 11, token & 0x7FF
            if not count:
                if p >= end:
                    return None
                count, distance = data[p] | data[p + 1] << 8, token
                p += 2
            if not distance:
                if not count:
                    return bytes(out)
                out += bytes(2 * count)
            else:
                start = len(out) - 2 * distance
                if start < 0:
                    return None
                for k in range(count):
                    out += out[start + 2 * k:start + 2 * k + 2]
        else:
            out += data[p:p + 2]
            p += 2
        bit >>= 1
    return None


def mwo3_image(data):
    """A Capcom MWo3 executable as it sits in memory, from a disc file holding it plain or compressed."""
    if data[:4] != b'MWo3':
        if data[2:6] != b'MWo3' or data[1] & 0xC0:
            return None
        data = capcom_lz(data)
        if not data or data[:4] != b'MWo3':
            return None
    if len(data) < 0x40:
        return None
    base, text, size = struct.unpack_from('<III', data, 8)
    image = 0x40 + text + size
    if image > len(data) or struct.unpack_from('<I', data, 0x18)[0] != base + image:
        return None
    return data[:image]


def overlays(image, destination, limit):
    """Writes the code overlays a game copies to fixed addresses at run time, decoded, for eerecomp --overlays."""
    found = []
    for path, _, sector, size in image.files():
        if size > limit:
            continue
        head = read_at(image, sector, 0, 8)
        if b'MWo3' not in head[:6]:
            continue
        data = mwo3_image(read_at(image, sector, 0, size))
        if data:
            found.append((path.replace('/', '_'), data))
    os.makedirs(destination, exist_ok=True)
    for name, data in found:
        with open(os.path.join(destination, name + '.ovl'), 'wb') as out:
            out.write(data)
    print('%d overlays extracted to %s' % (len(found), destination))


def main():
    parser = argparse.ArgumentParser(description='Read files from PS2 CD and DVD images, including both layers of a DVD9.')
    parser.add_argument('command', choices=['info', 'list', 'extract', 'modules', 'overlays'])
    parser.add_argument('image')
    parser.add_argument('destination', nargs='?')
    parser.add_argument('patterns', nargs='*', help='paths or globs to extract, everything below --limit if omitted')
    parser.add_argument('--limit', type=int, default=64, help='MiB; larger files stay in the image unless named')
    args = parser.parse_args()
    image = Image(args.image)
    if args.command == 'info':
        info(image)
    elif args.command == 'list':
        listing(image)
    elif args.command == 'modules':
        if not args.destination:
            parser.error('modules needs a destination folder')
        modules(image, args.destination)
    elif args.command == 'overlays':
        if not args.destination:
            parser.error('overlays needs a destination folder')
        overlays(image, args.destination, args.limit << 20)
    else:
        if not args.destination:
            parser.error('extract needs a destination folder')
        extract(image, args.destination, args.patterns, args.limit << 20)


if __name__ == '__main__':
    main()
