import unittest
from decimal import Decimal

from shop.pricing import apply_discount


class DiscountTests(unittest.TestCase):
    def test_discounts_are_decimals_rounded_half_up_to_cents(self):
        self.assertEqual(apply_discount(Decimal("5.35"), 50), Decimal("2.68"))
        self.assertEqual(apply_discount(Decimal("19.99"), 15), Decimal("16.99"))
        self.assertIsInstance(apply_discount("10", 10), Decimal)
