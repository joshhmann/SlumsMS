"""Third-party files served from our own server, pinned by hash: python3 -m unittest discover -s tools/site -p 'test_*.py'
To update one, replace the file, check the new version's notes, and change its hash here and in docs/site/FRAMEWORK.md."""
import hashlib
import os
import unittest

ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "site", "assets")
PINNED = {
    # alpinejs@3.14.1 dist/cdn.min.js from the npm registry (MIT), npm's integrity check passed on download
    "vendor/alpine-3.14.1.min.js": "358d9afbb1ab5befa2f48061a30776e5bcd7707f410a606ba985f98bc3b1c034",
}


class VendorTest(unittest.TestCase):
    def test_vendored_files_match_their_pinned_hash(self):
        for rel, want in PINNED.items():
            with open(os.path.join(ASSETS, rel), "rb") as f:
                self.assertEqual(hashlib.sha256(f.read()).hexdigest(), want, rel)

    def test_nothing_unpinned_in_vendor(self):
        self.assertEqual(sorted("vendor/" + n for n in os.listdir(os.path.join(ASSETS, "vendor"))), sorted(PINNED))


if __name__ == "__main__":
    unittest.main()
