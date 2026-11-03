"""Self-checks for ps2netcnf.py: python3 -m unittest tools/test_ps2netcnf.py"""
import os
import re
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import ps2netcnf as t  # noqa: E402

MCMAN = os.path.expanduser("~/reo-web/ref/ps2sdk/iop/memorycard/mcman/src/main.c")


class Codec(unittest.TestCase):
    def test_roundtrip(self):
        text = b"# <Sony Computer Entertainment Inc.>\n\ntype eth\ndhcp\nX"
        self.assertEqual(t.decode(t.encode(text)), text)
        self.assertEqual(t.decode(t.encode(text, bytes(range(8))), bytes(range(8))), text)

    def test_zero_id_is_rotate_by_one(self):
        # All-zero i.LINK ID: each word is rotated left by one and inverted.
        self.assertEqual(t.encode(b"\x01\x00"), bytes([0xFD, 0xFF]))
        self.assertEqual(t.encode(b"\x00\x80"), bytes([0xFE, 0xFF]))


class Ecc(unittest.TestCase):
    @unittest.skipUnless(os.path.exists(MCMAN), "ps2sdk reference not cloned")
    def test_table_matches_mcman(self):
        src = open(MCMAN).read()
        body = re.search(r"mcman_xortable\[256\] = \{(.*?)\};", src, re.S).group(1)
        self.assertEqual([int(x, 16) for x in re.findall(r"0x[0-9a-fA-F]+", body)], t._ECC_TABLE)


class CardImage(unittest.TestCase):
    def test_install_and_read_back(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "card.ps2")
            files = t.config_files()
            t.install(path, files)
            raw = open(path, "rb").read()
            self.assertEqual(len(raw), t.RAW_PAGE * t.PAGES)
            card = t.Card(raw)
            self.assertTrue(card.formatted())
            self.assertEqual(card.check_ecc(raw), 0)
            for name, data in files.items():
                e = card.lookup("/BWNETCNF/" + name)
                self.assertIsNotNone(e, name)
                self.assertEqual(card.read_chain(e[4], e[2]), data)
            e = card.lookup("/BWNETCNF/BWNETCNF")
            index = card.read_chain(e[4], e[2])
            self.assertIn(b"0,1,net000.cnf,Combination1", index)
            ifc = card.lookup("/BWNETCNF/ifc000.dat")
            self.assertIn(b"dhcp", t.decode(card.read_chain(ifc[4], ifc[2])))


if __name__ == "__main__":
    unittest.main()
