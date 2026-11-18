import unittest

from shop.textutil import slugify


class SlugifyTests(unittest.TestCase):
    def test_runs_of_separators_become_one_dash(self):
        self.assertEqual(slugify("Hello,  World!"), "hello-world")
        self.assertEqual(slugify("a__b"), "a-b")

    def test_no_dashes_at_the_ends(self):
        self.assertEqual(slugify("  --Already-Slugged--  "), "already-slugged")
        self.assertEqual(slugify("Version 2.0"), "version-2-0")
