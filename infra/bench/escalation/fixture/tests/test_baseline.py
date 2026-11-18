import datetime
import unittest
from decimal import Decimal

from shop import cart, dates, inventory, pricing, textutil


class BaselineTests(unittest.TestCase):
    def test_truncate_keeps_short_text_and_shortens_long_text(self):
        self.assertEqual(textutil.truncate("short", 10), "short")
        self.assertEqual(textutil.truncate("a longer sentence", 8), "a longe\u2026")

    def test_tax_is_added_and_rounded_to_cents(self):
        self.assertEqual(pricing.with_tax(Decimal("10")), Decimal("12.00"))

    def test_small_carts_have_no_discount(self):
        basket = cart.Cart()
        basket.add("pen", "2.50", 2)
        self.assertEqual(basket.total(), Decimal("6.00"))

    def test_cart_rejects_non_positive_quantities(self):
        with self.assertRaises(ValueError):
            cart.Cart().add("pen", "1.00", 0)

    def test_inventory_reports_stock(self):
        stock = inventory.Inventory({"pen": 3})
        self.assertEqual(stock.available("pen"), 3)
        self.assertEqual(stock.available("ink"), 0)

    def test_days_are_parsed_from_iso_text(self):
        self.assertEqual(dates.parse_day("2026-03-01"), datetime.date(2026, 3, 1))
